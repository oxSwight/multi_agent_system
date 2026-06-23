package com.midas.d3.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.midas.d3.agent.implementation.CodeGenerationCoordinator;
import com.midas.d3.agent.implementation.TestGenerationCoordinator;
import com.midas.d3.context.AgentContextView;
import com.midas.d3.context.ContextReducer;
import com.midas.d3.context.MidasContext;
import com.midas.d3.llm.LlmCallException;
import com.midas.d3.llm.LlmCallRequest;
import com.midas.d3.llm.LlmCallResult;
import com.midas.d3.llm.LlmClient;
import com.midas.d3.llm.LlmModelPolicy;
import com.midas.d3.sanitizer.JsonSanitizer;
import com.midas.d3.statemachine.MidasState;
import com.midas.d3.statemachine.PipelineOrchestrator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.Map;

/**
 * Orchestrates a single pipeline stage: calls the appropriate LLM agent, sanitizes
 * the output, and submits it to the state machine via {@link PipelineOrchestrator}.
 *
 * <h2>Flow per stage</h2>
 * <pre>
 *   getState(runId)
 *     ↓
 *   ContextReducer.reduce(context, role)  → AgentContextView
 *     ↓
 *   buildUserMessage(view)                → compact JSON string
 *     ↓
 *   AgentSystemPrompts.getPrompt(state)   → system prompt
 *     ↓
 *   LlmClient.call(request)               → raw LLM output
 *     ↓
 *   JsonSanitizer.sanitize(raw)           → cleaned JSON string
 *     ↓
 *   PipelineOrchestrator.submitResult()   → validated, stored, state advances
 * </pre>
 *
 * <h2>Error handling</h2>
 * {@link LlmCallException} is propagated as-is; callers decide on retry policy.
 * Sanitization never throws — it only cleans the string.
 * Validation failures are handled by the state machine (retry / error transition).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentOrchestrationService {

    private final PipelineOrchestrator  pipelineOrchestrator;
    private final ContextReducer        contextReducer;
    private final LlmClient             llmClient;
    private final LlmModelPolicy        llmModelPolicy;
    private final AgentSystemPrompts    agentSystemPrompts;
    private final ObjectMapper          objectMapper;
    private final CodeGenerationCoordinator codeGenerationCoordinator;
    private final TestGenerationCoordinator testGenerationCoordinator;

    /** Maps pipeline stages to their ContextReducer agent roles. */
    private static final Map<MidasState, ContextReducer.AgentRole> STAGE_TO_ROLE =
            new EnumMap<>(MidasState.class);

    /** Maps pipeline stages to human-readable agent names for logging. */
    private static final Map<MidasState, String> STAGE_TO_AGENT_NAME =
            new EnumMap<>(MidasState.class);

    static {
        STAGE_TO_ROLE.put(MidasState.SYSTEM_ANALYSIS,      ContextReducer.AgentRole.SYSTEM_ANALYST);
        STAGE_TO_ROLE.put(MidasState.ARCHITECTURE_DESIGN,  ContextReducer.AgentRole.SOFTWARE_ARCHITECT);
        STAGE_TO_ROLE.put(MidasState.INTEGRATION_STRATEGY, ContextReducer.AgentRole.INTEGRATION_ENGINEER);
        STAGE_TO_ROLE.put(MidasState.CODE_GENERATION,      ContextReducer.AgentRole.IMPLEMENTATION_ENGINEER);
        STAGE_TO_ROLE.put(MidasState.TEST_GENERATION,      ContextReducer.AgentRole.QA_ENGINEER);
        STAGE_TO_ROLE.put(MidasState.SECOPS_AUDIT,         ContextReducer.AgentRole.SECOPS_ENGINEER);
        STAGE_TO_ROLE.put(MidasState.PRODUCT_REVIEW,       ContextReducer.AgentRole.CONTROLLER);

        STAGE_TO_AGENT_NAME.put(MidasState.SYSTEM_ANALYSIS,      "SystemAnalyst");
        STAGE_TO_AGENT_NAME.put(MidasState.ARCHITECTURE_DESIGN,  "SoftwareArchitect");
        STAGE_TO_AGENT_NAME.put(MidasState.INTEGRATION_STRATEGY, "IntegrationEngineer");
        STAGE_TO_AGENT_NAME.put(MidasState.CODE_GENERATION,      "ImplementationEngineer");
        STAGE_TO_AGENT_NAME.put(MidasState.TEST_GENERATION,      "QaEngineer");
        STAGE_TO_AGENT_NAME.put(MidasState.SECOPS_AUDIT,         "SecOpsEngineer");
        STAGE_TO_AGENT_NAME.put(MidasState.PRODUCT_REVIEW,       "Controller");
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Executes the current pipeline stage for the given run:
     * reduces context → calls LLM → sanitizes output → submits to state machine.
     *
     * @param runId the pipeline run ID
     * @return the state AFTER the submission (may be the same state if validation fails
     *         but retries remain, or ERROR/COMPLETED)
     * @throws LlmCallException          if the LLM call fails
     * @throws IllegalArgumentException  if the current state has no registered agent
     * @throws PipelineOrchestrator.PipelineNotFoundException if runId is unknown
     */
    public MidasState runCurrentStage(String runId) throws LlmCallException {
        MidasState currentState = pipelineOrchestrator.getState(runId);
        log.info("[AgentOrchestrationService] Run [{}] — executing stage [{}].", runId, currentState);

        if (!STAGE_TO_ROLE.containsKey(currentState)) {
            throw new IllegalArgumentException(
                    "No agent configured for state [%s]. Is the pipeline in a valid processing stage?"
                            .formatted(currentState));
        }

        MidasContext context = pipelineOrchestrator.getContext(runId)
                .orElseThrow(() -> new IllegalStateException("MidasContext not found for run: " + runId));

        // Produce the payload for this stage. Code/test generation delegate to their
        // dedicated per-file coordinators; every other stage runs the generic
        // reduce → prompt → call → sanitize path. Both feed the same submit tail.
        String payload = switch (currentState) {
            case CODE_GENERATION ->
                    codeGenerationCoordinator.execute(context, "ImplementationEngineer").rawLlmOutput();
            case TEST_GENERATION ->
                    testGenerationCoordinator.execute(context, "QaEngineer").rawLlmOutput();
            default ->
                    runGenericLlmStage(runId, context, currentState);
        };

        // Submit to state machine — validation + state transition happen inside.
        pipelineOrchestrator.submitResult(runId, payload);

        MidasState newState = pipelineOrchestrator.getState(runId);
        log.info("[AgentOrchestrationService] Run [{}] — stage [{}] done. New state: [{}].",
                runId, currentState, newState);
        return newState;
    }

    /**
     * Runs the generic single-call LLM stage: reduce context → build user message →
     * resolve system prompt → enforce the prompt budget → call the LLM → sanitize.
     *
     * @return the sanitized payload ready to submit to the state machine
     * @throws LlmCallException if the LLM call fails (callers handle retries)
     */
    private String runGenericLlmStage(String runId, MidasContext context, MidasState stage)
            throws LlmCallException {
        // 1. Reduce context to minimal required artifacts
        ContextReducer.AgentRole role = STAGE_TO_ROLE.get(stage);
        AgentContextView view         = contextReducer.reduce(context, role);

        // 2. Build user message
        String userMessage = buildUserMessage(view, stage);

        // 3. Get system prompt
        String systemPrompt = agentSystemPrompts.getPrompt(stage)
                .orElseThrow(() -> new IllegalStateException(
                        "No system prompt registered for state: " + stage));

        String agentName = STAGE_TO_AGENT_NAME.get(stage);

        // 4. Fail-closed prompt-budget guard, then call LLM (may throw LlmCallException — callers handle retries)
        contextReducer.enforcePromptBudget(agentName, systemPrompt, userMessage);
        LlmCallResult llmResult = llmClient.call(
                LlmCallRequest.of(stage, agentName, systemPrompt, userMessage, runId,
                        llmModelPolicy.resolve(stage)));
        String rawOutput = llmResult.text();

        log.debug("[AgentOrchestrationService][{}] Raw LLM output ({} chars): {}…",
                agentName, rawOutput.length(),
                rawOutput.length() > 200 ? rawOutput.substring(0, 200) : rawOutput);

        // 5. Sanitize (strip markdown fences, extract first JSON object)
        String sanitized = JsonSanitizer.sanitize(rawOutput);

        log.debug("[AgentOrchestrationService][{}] Sanitized output ({} chars).",
                agentName, sanitized == null ? 0 : sanitized.length());

        return sanitized;
    }

    /**
     * Convenience: runs all stages until the pipeline reaches COMPLETED or ERROR.
     * Each stage is executed once; if validation fails the state machine handles retries
     * automatically on subsequent calls.
     *
     * <p>Callers should poll or loop externally if they want to retry failed stages.
     *
     * @return final pipeline state (COMPLETED or ERROR or last reached state)
     */
    public MidasState runFullPipeline(String runId) throws LlmCallException {
        MidasState state = pipelineOrchestrator.getState(runId);
        while (STAGE_TO_ROLE.containsKey(state)) {
            state = runCurrentStage(runId);
        }
        return state;
    }

    // ── User message builder ──────────────────────────────────────────────────

    /**
     * Builds a structured, compact user message for the LLM from the reduced context view.
     *
     * <p>The format is designed to be clear and concise — upstream artifacts are injected
     * as labeled JSON blocks so the LLM has explicit, labeled context.
     */
    String buildUserMessage(AgentContextView view, MidasState stage) {
        StringBuilder sb = new StringBuilder();
        sb.append("USER IDEA:\n").append(view.getRawUserIdea()).append("\n\n");

        Map<String, JsonNode> artifacts = view.safeArtifacts();

        if (!artifacts.isEmpty()) {
            sb.append("UPSTREAM CONTEXT:\n");
            artifacts.forEach((key, node) -> {
                sb.append("## ").append(toLabel(key)).append("\n");
                sb.append(prettyPrint(node)).append("\n\n");
            });
        }

        sb.append("TASK: Based on the above, produce the output for the [")
          .append(STAGE_TO_AGENT_NAME.getOrDefault(stage, stage.name()))
          .append("] stage. Follow the system prompt schema exactly.");

        return sb.toString();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String prettyPrint(JsonNode node) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (Exception e) {
            return node.toString();
        }
    }

    private String toLabel(String artifactKey) {
        return switch (artifactKey) {
            case "technicalSpec"       -> "Technical Specification";
            case "architectureDesign"  -> "Architecture Design";
            case "integrationStrategy" -> "Integration Strategy";
            case "generatedSourceCode" -> "Generated Source Code";
            case "generatedTests"      -> "Generated Tests";
            case "secOpsArtifacts"     -> "SecOps & Release Artifacts";
            default -> artifactKey;
        };
    }

    /** Returns true if the given state is an active processing stage. */
    public boolean isProcessingStage(MidasState state) {
        return STAGE_TO_ROLE.containsKey(state);
    }
}

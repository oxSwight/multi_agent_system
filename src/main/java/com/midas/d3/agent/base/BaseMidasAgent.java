package com.midas.d3.agent.base;

import com.fasterxml.jackson.databind.JsonNode;
import com.midas.d3.agent.AgentSystemPrompts;
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
import com.midas.d3.statemachine.ValidatorRegistry;
import com.midas.d3.validation.ControllerValidator;
import com.midas.d3.validation.GoalKeeperValidator;
import com.midas.d3.validation.ValidationHookException;
import lombok.extern.slf4j.Slf4j;

import java.util.EnumMap;
import java.util.Map;

/**
 * Abstract base for all MIDAS LLM agents.
 *
 * <h2>Template Method pattern</h2>
 * Subclasses declare their identity ({@link #getRole()}, {@link #getAgentName()},
 * {@link #getSystemPrompt()}) and inherit the entire execution protocol:
 *
 * <pre>
 *   ContextReducer.reduce(context, role)        → AgentContextView
 *     ↓
 *   LlmClient.call(systemPrompt, message)       → raw LLM text
 *     ↓
 *   JsonSanitizer.sanitize(raw)                 → cleaned JSON string
 *     ↓
 *   GoalKeeperValidator.validate(sanitized)     → validated JsonNode
 *     ↓  if ValidationHookException → inject error into next message, retry
 *   AgentResult (validatedOutput, rawOutput, attemptsUsed)
 * </pre>
 *
 * <h2>Internal retry loop</h2>
 * Up to {@value #MAX_AGENT_RETRIES} attempts per execution. On each failed attempt
 * the validation error is appended to the user message so the LLM can self-correct.
 * Retryable {@link LlmCallException}s (network/timeout/429/5xx) are also retried.
 * Non-retryable LLM errors propagate immediately.
 *
 * <p>After all retries are exhausted, {@link AgentExecutionException} is thrown.
 */
@Slf4j
public abstract class BaseMidasAgent {

    /** Maximum LLM call attempts per {@link #execute(MidasContext)} invocation. */
    static final int MAX_AGENT_RETRIES = 3;

    private static final Map<ContextReducer.AgentRole, MidasState> ROLE_TO_STATE =
            new EnumMap<>(ContextReducer.AgentRole.class);

    static {
        ROLE_TO_STATE.put(ContextReducer.AgentRole.SYSTEM_ANALYST,      MidasState.SYSTEM_ANALYSIS);
        ROLE_TO_STATE.put(ContextReducer.AgentRole.SOFTWARE_ARCHITECT,   MidasState.ARCHITECTURE_DESIGN);
        ROLE_TO_STATE.put(ContextReducer.AgentRole.INTEGRATION_ENGINEER, MidasState.INTEGRATION_STRATEGY);
        ROLE_TO_STATE.put(ContextReducer.AgentRole.IMPLEMENTATION_ENGINEER, MidasState.CODE_GENERATION);
        ROLE_TO_STATE.put(ContextReducer.AgentRole.QA_ENGINEER,          MidasState.TEST_GENERATION);
        ROLE_TO_STATE.put(ContextReducer.AgentRole.SECOPS_ENGINEER,      MidasState.SECOPS_AUDIT);
        ROLE_TO_STATE.put(ContextReducer.AgentRole.CONTROLLER,           MidasState.PRODUCT_REVIEW);
    }

    protected final LlmClient         llmClient;
    protected final ContextReducer    contextReducer;
    protected final ValidatorRegistry validatorRegistry;
    protected final LlmModelPolicy      llmModelPolicy;

    protected BaseMidasAgent(LlmClient         llmClient,
                             ContextReducer    contextReducer,
                             ValidatorRegistry validatorRegistry,
                             LlmModelPolicy    llmModelPolicy) {
        this.llmClient        = llmClient;
        this.contextReducer   = contextReducer;
        this.validatorRegistry = validatorRegistry;
        this.llmModelPolicy   = llmModelPolicy;
    }

    // ── Abstract template methods ─────────────────────────────────────────────

    /** The ContextReducer role that identifies which upstream artifacts this agent needs. */
    public abstract ContextReducer.AgentRole getRole();

    /**
     * Canonical agent type identifier — used for logging, error messages, persistence
     * ({@code midas_agent_log.agent_type}) and dashboard/priority lookups.
     *
     * <p>By convention this MUST equal the concrete agent's simple class name (the
     * {@code ...Agent} suffix), e.g. {@code "SystemAnalystAgent"}. The dashboard stage
     * map and the EvolutionAgent priority list key off this exact suffixed form, so a
     * non-suffixed value would silently break stage indexing and report ordering.
     */
    public abstract String getAgentName();

    /** Full system prompt that is sent to the LLM on every call. */
    public abstract String getSystemPrompt();

    /**
     * Optional hook for agents whose execution protocol differs from the default single-pass loop.
     * Return non-null to bypass {@link #execute(MidasContext)}'s built-in retry protocol entirely.
     */
    protected AgentResult tryCustomExecution(MidasContext context) {
        return null;
    }

    // ── Template method (sealed execution protocol) ───────────────────────────

    /**
     * Executes this agent against the provided pipeline context.
     *
     * @param context full pipeline context; required upstream artifacts must be present
     * @return {@link AgentResult} containing the validated JSON artifact
     * @throws AgentExecutionException if all {@value #MAX_AGENT_RETRIES} attempts fail
     * @throws LlmCallException        if a non-retryable transport error occurs
     */
    public final AgentResult execute(MidasContext context) {
        AgentResult custom = tryCustomExecution(context);
        if (custom != null) {
            return custom;
        }

        MidasState stage = resolveStage();

        AgentContextView view = contextReducer.reduce(context, getRole());
        String baseUserMessage = buildUserMessage(view);

        GoalKeeperValidator validator = validatorRegistry.getValidator(stage)
                .orElseThrow(() -> new IllegalStateException(
                        "No GoalKeeperValidator registered for stage [%s] — check ValidatorRegistry."
                                .formatted(stage)));

        String lastError = null;
        int totalPromptTokens = 0;
        int totalCompletionTokens = 0;
        String modelUsed = "";

        for (int attempt = 1; attempt <= MAX_AGENT_RETRIES; attempt++) {
            String userMessage = (attempt == 1)
                    ? baseUserMessage
                    : injectCorrectionFeedback(baseUserMessage, lastError, attempt);

            log.info("[{}] Attempt {}/{} — run=[{}]",
                    getAgentName(), attempt, MAX_AGENT_RETRIES, context.getPipelineRunId());

            try {
                LlmCallResult llmResult = llmClient.call(
                        LlmCallRequest.of(stage, getAgentName(), effectiveSystemPrompt(context),
                                userMessage, context.getPipelineRunId(), llmModelPolicy.resolve(stage)));
                totalPromptTokens += llmResult.promptTokens();
                totalCompletionTokens += llmResult.completionTokens();
                modelUsed = llmResult.modelUsed();
                String raw = llmResult.text();

                // ── Human-in-the-Loop early-exit ─────────────────────────────
                // If the LLM signals insufficient input with [NEED_INFO], bypass
                // JSON validation entirely and return a NeedsInfo result so the
                // AgentEntryAction can route the machine to WAITING_FOR_USER_INPUT.
                if (raw != null && raw.stripLeading().startsWith(AgentResult.NEED_INFO_PREFIX)) {
                    log.info("[{}] Analyst returned [NEED_INFO] on attempt {}/{} — pausing for user input.",
                            getAgentName(), attempt, MAX_AGENT_RETRIES);
                    return new AgentResult(null, raw.strip(), attempt, totalPromptTokens, totalCompletionTokens, modelUsed);
                }

                String sanitized = JsonSanitizer.sanitize(raw);

                log.debug("[{}] Attempt {}/{} — sanitized {} chars.",
                        getAgentName(), attempt, MAX_AGENT_RETRIES,
                        sanitized == null ? 0 : sanitized.length());

                JsonNode validated = validateAgentOutput(stage, sanitized, validator, context);

                log.info("[{}] Attempt {}/{} — validation passed.", getAgentName(), attempt, MAX_AGENT_RETRIES);
                return new AgentResult(validated, sanitized, attempt, totalPromptTokens, totalCompletionTokens, modelUsed);

            } catch (ValidationHookException e) {
                lastError = String.join(" | ", e.getViolations());
                log.warn("[{}] Attempt {}/{} — GoalKeeper rejected output: {}",
                        getAgentName(), attempt, MAX_AGENT_RETRIES, lastError);
                backoffBeforeRetry(attempt);

            } catch (LlmCallException e) {
                if (!e.isRetryable()) {
                    log.error("[{}] Non-retryable LLM error on attempt {}/{}: {}",
                            getAgentName(), attempt, MAX_AGENT_RETRIES, e.getMessage());
                    throw e;
                }
                lastError = "LLM transport error: " + e.getMessage();
                log.warn("[{}] Attempt {}/{} — retryable LLM error, will retry: {}",
                        getAgentName(), attempt, MAX_AGENT_RETRIES, e.getMessage());
                backoffBeforeRetry(attempt);
            }
        }

        throw new AgentExecutionException(getAgentName(), getRole(), MAX_AGENT_RETRIES, lastError);
    }

    // ── Helper methods ────────────────────────────────────────────────────────

    /**
     * Derives the pipeline {@link MidasState} for this agent from its {@link ContextReducer.AgentRole}.
     * Public so that {@link com.midas.d3.statemachine.action.AgentEntryAction} can build the state → agent map.
     */
    public MidasState resolveStage() {
        MidasState stage = ROLE_TO_STATE.get(getRole());
        if (stage == null) {
            throw new IllegalStateException(
                    "No MidasState mapping found for AgentRole [%s].".formatted(getRole()));
        }
        return stage;
    }

    private JsonNode validateAgentOutput(MidasState stage,
                                         String sanitized,
                                         GoalKeeperValidator validator,
                                         MidasContext context) throws ValidationHookException {
        if (stage == MidasState.PRODUCT_REVIEW && validator instanceof ControllerValidator controllerValidator) {
            return controllerValidator.validateWithFeatureManifest(sanitized, context.getFeatureManifest());
        }
        return validator.validate(sanitized);
    }

    private String effectiveSystemPrompt(MidasContext context) {
        if (getRole() == ContextReducer.AgentRole.SECOPS_ENGINEER) {
            return AgentSystemPrompts.appendProductReviewRemediation(
                    getSystemPrompt(), context.getRemediationDirective());
        }
        return getSystemPrompt();
    }

    /**
     * Builds a structured user message from the reduced context view.
     * Upstream artifacts are included as labeled JSON blocks.
     */
    private String buildUserMessage(AgentContextView view) {
        StringBuilder sb = new StringBuilder();
        sb.append("USER IDEA:\n").append(view.getRawUserIdea()).append("\n\n");

        Map<String, JsonNode> artifacts = view.safeArtifacts();
        if (!artifacts.isEmpty()) {
            sb.append("UPSTREAM ARTIFACTS:\n");
            artifacts.forEach((key, node) ->
                    sb.append("## ").append(key).append("\n")
                      .append(node.toPrettyString()).append("\n\n"));
        }

        sb.append("TASK: Produce the output for the [")
          .append(getAgentName())
          .append("] stage. Follow the system prompt JSON schema exactly.");
        return sb.toString();
    }

    private void backoffBeforeRetry(int attempt) {
        if (attempt >= MAX_AGENT_RETRIES) return;
        sleepQuietly(3_000L * attempt);
    }

    private void sleepQuietly(long delayMs) {
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Appends GoalKeeper error feedback to the user message so the LLM can self-correct.
     */
    private String injectCorrectionFeedback(String baseUserMessage, String error, int attempt) {
        return baseUserMessage
                + "\n\n"
                + "--- CORRECTION REQUIRED (attempt " + attempt + " of " + MAX_AGENT_RETRIES + ") ---\n"
                + "Your previous response was rejected by the schema validator.\n"
                + "Violations found:\n"
                + error + "\n"
                + "Fix ALL violations. Output ONLY the corrected JSON object.";
    }
}

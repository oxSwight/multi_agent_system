package com.midas.d3.agent.implementation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.midas.d3.agent.AgentSystemPrompts;
import com.midas.d3.agent.base.AgentExecutionException;
import com.midas.d3.agent.base.AgentResult;
import com.midas.d3.config.AsyncConfig;
import com.midas.d3.context.AgentContextView;
import com.midas.d3.context.ContextReducer;
import com.midas.d3.context.MidasContext;
import com.midas.d3.llm.LlmCallException;
import com.midas.d3.llm.LlmCallResult;
import com.midas.d3.llm.LlmCallRequest;
import com.midas.d3.llm.LlmClient;
import com.midas.d3.llm.LlmModelPolicy;
import com.midas.d3.sanitizer.JsonSanitizer;
import com.midas.d3.statemachine.MidasState;
import com.midas.d3.statemachine.ValidatorRegistry;
import com.midas.d3.validation.GoalKeeperValidator;
import com.midas.d3.validation.ValidationHookException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

/**
 * Coordinates {@link MidasState#CODE_GENERATION}, including the bounded HYBRID fan-out
 * into client and server implementation passes that merge into one {@code generatedSourceCode} map.
 */
@Slf4j
@Service
public class CodeGenerationCoordinator {

    static final int MAX_PASS_RETRIES = 3;

    private final ContextReducer contextReducer;
    private final LlmClient llmClient;
    private final LlmModelPolicy llmModelPolicy;
    private final ValidatorRegistry validatorRegistry;
    private final ObjectMapper objectMapper;
    private final Executor agentTaskExecutor;

    public CodeGenerationCoordinator(ContextReducer contextReducer,
                                     LlmClient llmClient,
                                     LlmModelPolicy llmModelPolicy,
                                     ValidatorRegistry validatorRegistry,
                                     ObjectMapper objectMapper,
                                     @Qualifier(AsyncConfig.AGENT_EXECUTOR) Executor agentTaskExecutor) {
        this.contextReducer = contextReducer;
        this.llmClient = llmClient;
        this.llmModelPolicy = llmModelPolicy;
        this.validatorRegistry = validatorRegistry;
        this.objectMapper = objectMapper;
        this.agentTaskExecutor = agentTaskExecutor;
    }

    /**
     * Executes code generation — single pass for standard models, dual pass + merge for HYBRID.
     */
    public AgentResult execute(MidasContext context, String agentName) {
        GoalKeeperValidator validator = validatorRegistry.getValidator(MidasState.CODE_GENERATION)
                .orElseThrow(() -> new IllegalStateException(
                        "No GoalKeeperValidator registered for CODE_GENERATION."));

        if (HybridExecutionModel.isHybrid(context)) {
            return executeHybridFanOut(context, agentName, validator);
        }
        return HybridExecutionModel.singlePassSurface(context)
                .map(surface -> executeSurfaceSinglePass(context, agentName, surface, validator))
                .orElseGet(() -> executeSinglePass(
                        context,
                        agentName,
                        AgentSystemPrompts.IMPLEMENTATION_ENGINEER_PROMPT,
                        contextReducer.reduce(context, ContextReducer.AgentRole.IMPLEMENTATION_ENGINEER),
                        validator,
                        "ImplementationEngineer"));
    }

    private AgentResult executeSurfaceSinglePass(MidasContext context,
                                                 String agentName,
                                                 ImplementationSurface surface,
                                                 GoalKeeperValidator validator) {
        String systemPrompt = surface == ImplementationSurface.CLIENT
                ? AgentSystemPrompts.HYBRID_CLIENT_IMPLEMENTATION_PROMPT
                : AgentSystemPrompts.HYBRID_SERVER_IMPLEMENTATION_PROMPT;
        PassResult pass = executePass(context, surface, systemPrompt, agentName, validator);
        String json;
        try {
            json = objectMapper.writeValueAsString(pass.validated());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize " + surface + " source map.", e);
        }
        return new AgentResult(pass.validated(), json, pass.attemptsUsed(),
                pass.promptTokens(), pass.completionTokens(), pass.modelId());
    }

    private AgentResult executeHybridFanOut(MidasContext context,
                                            String agentName,
                                            GoalKeeperValidator validator) {
        log.info("[CodeGenerationCoordinator] HYBRID parallel fan-out for run [{}].", context.getPipelineRunId());

        CompletableFuture<PassResult> clientFuture = CompletableFuture.supplyAsync(
                () -> executePass(
                        context,
                        ImplementationSurface.CLIENT,
                        AgentSystemPrompts.HYBRID_CLIENT_IMPLEMENTATION_PROMPT,
                        agentName + "Client",
                        validator),
                agentTaskExecutor);
        CompletableFuture<PassResult> serverFuture = CompletableFuture.supplyAsync(
                () -> executePass(
                        context,
                        ImplementationSurface.SERVER,
                        AgentSystemPrompts.HYBRID_SERVER_IMPLEMENTATION_PROMPT,
                        agentName + "Server",
                        validator),
                agentTaskExecutor);

        awaitAll(clientFuture, serverFuture);
        PassResult clientPass = clientFuture.join();
        PassResult serverPass = serverFuture.join();

        JsonNode merged = ImplementationSourceMerger.merge(
                clientPass.validated(), serverPass.validated(), objectMapper);

        String mergedJson;
        try {
            mergedJson = objectMapper.writeValueAsString(merged);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize merged HYBRID source map.", e);
        }

        validator.validate(mergedJson);

        int totalAttempts = clientPass.attemptsUsed() + serverPass.attemptsUsed();
        log.info("[CodeGenerationCoordinator] HYBRID fan-out complete for run [{}] — {} client files, {} server files.",
                context.getPipelineRunId(),
                clientPass.validated().size(),
                serverPass.validated().size());

        return new AgentResult(merged, mergedJson, totalAttempts,
                clientPass.promptTokens() + serverPass.promptTokens(),
                clientPass.completionTokens() + serverPass.completionTokens(),
                clientPass.modelId());
    }

    private PassResult executePass(MidasContext context,
                                   ImplementationSurface surface,
                                   String systemPrompt,
                                   String llmAgentName,
                                   GoalKeeperValidator validator) {
        AgentContextView view = contextReducer.reduceImplementationPass(context, surface);
        String baseUserMessage = buildUserMessage(view, surface);
        String modelOverride = llmModelPolicy.resolve(MidasState.CODE_GENERATION);
        String lastError = null;
        int totalPromptTokens = 0;
        int totalCompletionTokens = 0;

        for (int attempt = 1; attempt <= MAX_PASS_RETRIES; attempt++) {
            String userMessage = attempt == 1
                    ? baseUserMessage
                    : injectCorrectionFeedback(baseUserMessage, lastError, attempt);

            log.info("[CodeGenerationCoordinator] {} pass attempt {}/{} — run=[{}]",
                    surface, attempt, MAX_PASS_RETRIES, context.getPipelineRunId());

            try {
                LlmCallResult llmResult = llmClient.call(LlmCallRequest.of(
                        MidasState.CODE_GENERATION,
                        llmAgentName,
                        effectiveSystemPrompt(context, systemPrompt),
                        userMessage,
                        context.getPipelineRunId(),
                        modelOverride));
                totalPromptTokens += llmResult.promptTokens();
                totalCompletionTokens += llmResult.completionTokens();
                String raw = llmResult.text();

                String sanitized = JsonSanitizer.sanitize(raw);
                JsonNode validated = validator.validate(sanitized);
                return new PassResult(validated, attempt, totalPromptTokens, totalCompletionTokens, llmResult.modelUsed());

            } catch (ValidationHookException e) {
                lastError = String.join(" | ", e.getViolations());
                log.warn("[CodeGenerationCoordinator] {} pass attempt {}/{} rejected: {}",
                        surface, attempt, MAX_PASS_RETRIES, lastError);
                backoffBeforeRetry(attempt);

            } catch (LlmCallException e) {
                if (!e.isRetryable()) {
                    throw e;
                }
                lastError = "LLM transport error: " + e.getMessage();
                log.warn("[CodeGenerationCoordinator] {} pass attempt {}/{} transport error: {}",
                        surface, attempt, MAX_PASS_RETRIES, e.getMessage());
                backoffBeforeRetry(attempt);
            }
        }

        throw new AgentExecutionException(
                llmAgentName,
                ContextReducer.AgentRole.IMPLEMENTATION_ENGINEER,
                MAX_PASS_RETRIES,
                surface + " pass failed: " + lastError);
    }

    private AgentResult executeSinglePass(MidasContext context,
                                          String agentName,
                                          String systemPrompt,
                                          AgentContextView view,
                                          GoalKeeperValidator validator,
                                          String llmAgentName) {
        String baseUserMessage = buildUserMessage(view, null);
        String modelOverride = llmModelPolicy.resolve(MidasState.CODE_GENERATION);
        String lastError = null;
        int totalPromptTokens = 0;
        int totalCompletionTokens = 0;

        for (int attempt = 1; attempt <= MAX_PASS_RETRIES; attempt++) {
            String userMessage = attempt == 1
                    ? baseUserMessage
                    : injectCorrectionFeedback(baseUserMessage, lastError, attempt);

            log.info("[CodeGenerationCoordinator] Single pass attempt {}/{} — run=[{}]",
                    attempt, MAX_PASS_RETRIES, context.getPipelineRunId());

            try {
                LlmCallResult llmResult = llmClient.call(LlmCallRequest.of(
                        MidasState.CODE_GENERATION,
                        llmAgentName,
                        effectiveSystemPrompt(context, systemPrompt),
                        userMessage,
                        context.getPipelineRunId(),
                        modelOverride));
                totalPromptTokens += llmResult.promptTokens();
                totalCompletionTokens += llmResult.completionTokens();
                String raw = llmResult.text();

                String sanitized = JsonSanitizer.sanitize(raw);
                JsonNode validated = validator.validate(sanitized);
                return new AgentResult(validated, sanitized, attempt, totalPromptTokens, totalCompletionTokens, llmResult.modelUsed());

            } catch (ValidationHookException e) {
                lastError = String.join(" | ", e.getViolations());
                log.warn("[CodeGenerationCoordinator] Single pass attempt {}/{} rejected: {}",
                        attempt, MAX_PASS_RETRIES, lastError);
                backoffBeforeRetry(attempt);

            } catch (LlmCallException e) {
                if (!e.isRetryable()) {
                    throw e;
                }
                lastError = "LLM transport error: " + e.getMessage();
                backoffBeforeRetry(attempt);
            }
        }

        throw new AgentExecutionException(
                agentName,
                ContextReducer.AgentRole.IMPLEMENTATION_ENGINEER,
                MAX_PASS_RETRIES,
                lastError);
    }

    private String effectiveSystemPrompt(MidasContext context, String baseSystemPrompt) {
        return AgentSystemPrompts.appendProductReviewRemediation(
                baseSystemPrompt, context.getRemediationDirective());
    }

    String buildUserMessage(AgentContextView view, ImplementationSurface surface) {
        StringBuilder sb = new StringBuilder();
        sb.append("USER IDEA:\n").append(view.getRawUserIdea()).append("\n\n");

        if (surface != null) {
            sb.append("IMPLEMENTATION PASS: ")
              .append(surface.name())
              .append(" — implement ONLY the ")
              .append(surface == ImplementationSurface.CLIENT ? "client" : "server")
              .append(" surface described in the sliced architecture below.\n\n");
        }

        Map<String, JsonNode> artifacts = view.safeArtifacts();
        if (!artifacts.isEmpty()) {
            sb.append("UPSTREAM ARTIFACTS:\n");
            artifacts.forEach((key, node) ->
                    sb.append("## ").append(key).append("\n")
                      .append(node.toPrettyString()).append("\n\n"));
        }

        sb.append("TASK: Produce the output for the [ImplementationEngineer")
          .append(surface != null ? "/" + surface.name() : "")
          .append("] stage. Follow the system prompt JSON schema exactly.");
        return sb.toString();
    }

    private static String injectCorrectionFeedback(String baseUserMessage, String error, int attempt) {
        return baseUserMessage
                + "\n\n"
                + "--- CORRECTION REQUIRED (attempt " + attempt + " of " + MAX_PASS_RETRIES + ") ---\n"
                + "Your previous response was rejected by the schema validator.\n"
                + "Violations found:\n"
                + error + "\n"
                + "Fix ALL violations. Output ONLY the corrected JSON object.";
    }

    private static void backoffBeforeRetry(int attempt) {
        if (attempt >= MAX_PASS_RETRIES) {
            return;
        }
        sleepQuietly(3_000L * attempt);
    }

    private static void sleepQuietly(long delayMs) {
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private static void awaitAll(CompletableFuture<?>... futures) {
        try {
            CompletableFuture.allOf(futures).join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw e;
        }
    }

    private record PassResult(JsonNode validated, int attemptsUsed, int promptTokens, int completionTokens, String modelId) {}
}

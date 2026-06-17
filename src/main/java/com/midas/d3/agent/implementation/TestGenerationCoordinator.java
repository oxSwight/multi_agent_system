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
import com.midas.d3.llm.LlmCallRequest;
import com.midas.d3.llm.LlmClient;
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

@Slf4j
@Service
public class TestGenerationCoordinator {

    static final int MAX_PASS_RETRIES = 3;

    private final ContextReducer contextReducer;
    private final LlmClient llmClient;
    private final ValidatorRegistry validatorRegistry;
    private final ObjectMapper objectMapper;
    private final Executor agentTaskExecutor;

    public TestGenerationCoordinator(ContextReducer contextReducer,
                                     LlmClient llmClient,
                                     ValidatorRegistry validatorRegistry,
                                     ObjectMapper objectMapper,
                                     @Qualifier(AsyncConfig.AGENT_EXECUTOR) Executor agentTaskExecutor) {
        this.contextReducer = contextReducer;
        this.llmClient = llmClient;
        this.validatorRegistry = validatorRegistry;
        this.objectMapper = objectMapper;
        this.agentTaskExecutor = agentTaskExecutor;
    }

    public AgentResult execute(MidasContext context, String agentName) {
        GoalKeeperValidator validator = validatorRegistry.getValidator(MidasState.TEST_GENERATION)
                .orElseThrow(() -> new IllegalStateException(
                        "No GoalKeeperValidator registered for TEST_GENERATION."));

        if (HybridExecutionModel.isHybrid(context)) {
            return executeHybridFanOut(context, agentName, validator);
        }
        return executeSinglePass(
                context,
                agentName,
                AgentSystemPrompts.QA_ENGINEER_PROMPT,
                contextReducer.reduce(context, ContextReducer.AgentRole.QA_ENGINEER),
                validator,
                agentName);
    }

    private AgentResult executeHybridFanOut(MidasContext context,
                                            String agentName,
                                            GoalKeeperValidator validator) {
        log.info("[TestGenerationCoordinator] HYBRID parallel fan-out for run [{}].", context.getPipelineRunId());

        CompletableFuture<PassResult> clientFuture = CompletableFuture.supplyAsync(
                () -> executePass(
                        context,
                        ImplementationSurface.CLIENT,
                        AgentSystemPrompts.HYBRID_CLIENT_QA_PROMPT,
                        agentName + "Client",
                        validator),
                agentTaskExecutor);
        CompletableFuture<PassResult> serverFuture = CompletableFuture.supplyAsync(
                () -> executePass(
                        context,
                        ImplementationSurface.SERVER,
                        AgentSystemPrompts.HYBRID_SERVER_QA_PROMPT,
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
            throw new IllegalStateException("Failed to serialize merged HYBRID test map.", e);
        }

        validator.validate(mergedJson);

        int totalAttempts = clientPass.attemptsUsed() + serverPass.attemptsUsed();
        log.info("[TestGenerationCoordinator] HYBRID fan-out complete for run [{}] — {} client tests, {} server tests.",
                context.getPipelineRunId(),
                clientPass.validated().size(),
                serverPass.validated().size());

        return new AgentResult(merged, mergedJson, totalAttempts);
    }

    private PassResult executePass(MidasContext context,
                                   ImplementationSurface surface,
                                   String systemPrompt,
                                   String llmAgentName,
                                   GoalKeeperValidator validator) {
        AgentContextView view = contextReducer.reduceTestGenerationPass(context, surface);
        String baseUserMessage = buildUserMessage(view, surface);
        String lastError = null;

        for (int attempt = 1; attempt <= MAX_PASS_RETRIES; attempt++) {
            String userMessage = attempt == 1
                    ? baseUserMessage
                    : injectCorrectionFeedback(baseUserMessage, lastError, attempt);

            log.info("[TestGenerationCoordinator] {} pass attempt {}/{} — run=[{}]",
                    surface, attempt, MAX_PASS_RETRIES, context.getPipelineRunId());

            try {
                String raw = llmClient.call(LlmCallRequest.of(
                        MidasState.TEST_GENERATION,
                        llmAgentName,
                        systemPrompt,
                        userMessage,
                        context.getPipelineRunId()));

                String sanitized = JsonSanitizer.sanitize(raw);
                JsonNode validated = validator.validate(sanitized);
                return new PassResult(validated, attempt);

            } catch (ValidationHookException e) {
                lastError = String.join(" | ", e.getViolations());
                log.warn("[TestGenerationCoordinator] {} pass attempt {}/{} rejected: {}",
                        surface, attempt, MAX_PASS_RETRIES, lastError);
                backoffBeforeRetry(attempt);

            } catch (LlmCallException e) {
                if (!e.isRetryable()) {
                    throw e;
                }
                lastError = "LLM transport error: " + e.getMessage();
                log.warn("[TestGenerationCoordinator] {} pass attempt {}/{} transport error: {}",
                        surface, attempt, MAX_PASS_RETRIES, e.getMessage());
                if (e.getHttpStatus() == 429) {
                    sleepQuietly(45_000L);
                } else {
                    backoffBeforeRetry(attempt);
                }
            }
        }

        throw new AgentExecutionException(
                llmAgentName,
                ContextReducer.AgentRole.QA_ENGINEER,
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
        String lastError = null;

        for (int attempt = 1; attempt <= MAX_PASS_RETRIES; attempt++) {
            String userMessage = attempt == 1
                    ? baseUserMessage
                    : injectCorrectionFeedback(baseUserMessage, lastError, attempt);

            log.info("[TestGenerationCoordinator] Single pass attempt {}/{} — run=[{}]",
                    attempt, MAX_PASS_RETRIES, context.getPipelineRunId());

            try {
                String raw = llmClient.call(LlmCallRequest.of(
                        MidasState.TEST_GENERATION,
                        llmAgentName,
                        systemPrompt,
                        userMessage,
                        context.getPipelineRunId()));

                String sanitized = JsonSanitizer.sanitize(raw);
                JsonNode validated = validator.validate(sanitized);
                return new AgentResult(validated, sanitized, attempt);

            } catch (ValidationHookException e) {
                lastError = String.join(" | ", e.getViolations());
                log.warn("[TestGenerationCoordinator] Single pass attempt {}/{} rejected: {}",
                        attempt, MAX_PASS_RETRIES, lastError);
                backoffBeforeRetry(attempt);

            } catch (LlmCallException e) {
                if (!e.isRetryable()) {
                    throw e;
                }
                lastError = "LLM transport error: " + e.getMessage();
                if (e.getHttpStatus() == 429) {
                    sleepQuietly(45_000L);
                } else {
                    backoffBeforeRetry(attempt);
                }
            }
        }

        throw new AgentExecutionException(
                agentName,
                ContextReducer.AgentRole.QA_ENGINEER,
                MAX_PASS_RETRIES,
                lastError);
    }

    String buildUserMessage(AgentContextView view, ImplementationSurface surface) {
        StringBuilder sb = new StringBuilder();
        sb.append("USER IDEA:\n").append(view.getRawUserIdea()).append("\n\n");

        if (surface != null) {
            sb.append("TEST GENERATION PASS: ")
              .append(surface.name())
              .append(" — write tests ONLY for the ")
              .append(surface == ImplementationSurface.CLIENT ? "client" : "server")
              .append(" surface in the sliced source code below.\n\n");
        }

        Map<String, JsonNode> artifacts = view.safeArtifacts();
        if (!artifacts.isEmpty()) {
            sb.append("UPSTREAM ARTIFACTS:\n");
            artifacts.forEach((key, node) ->
                    sb.append("## ").append(key).append("\n")
                      .append(node.toPrettyString()).append("\n\n"));
        }

        sb.append("TASK: Produce the output for the [QaAutomationAgent")
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

    private record PassResult(JsonNode validated, int attemptsUsed) {}
}

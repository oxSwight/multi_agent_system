package com.midas.d3.agent.implementation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.midas.d3.agent.AgentRetryPolicy;
import com.midas.d3.agent.AgentSystemPrompts;
import com.midas.d3.agent.base.AgentExecutionException;
import com.midas.d3.agent.base.AgentResult;
import com.midas.d3.config.AsyncConfig;
import com.midas.d3.context.AgentContextView;
import com.midas.d3.context.ContextReducer;
import com.midas.d3.context.MidasContext;
import com.midas.d3.llm.LlmCallException;
import com.midas.d3.llm.LlmCallObservability;
import com.midas.d3.llm.LlmCallResult;
import com.midas.d3.llm.LlmCallRequest;
import com.midas.d3.llm.LlmClient;
import com.midas.d3.llm.LlmModelPolicy;
import com.midas.d3.sanitizer.JsonSanitizer;
import com.midas.d3.statemachine.MidasState;
import com.midas.d3.statemachine.ValidatorRegistry;
import com.midas.d3.validation.GoalKeeperValidator;
import com.midas.d3.validation.QaEngineerValidator;
import com.midas.d3.validation.ValidationHookException;
import com.midas.d3.statemachine.remediation.RemediationDirectiveSupport;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
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

    static final int MAX_PASS_RETRIES = AgentRetryPolicy.MAX_VALIDATION_ATTEMPTS;

    private final ContextReducer contextReducer;
    private final LlmClient llmClient;
    private final LlmModelPolicy llmModelPolicy;
    private final ValidatorRegistry validatorRegistry;
    private final ObjectMapper objectMapper;
    private final Executor agentTaskExecutor;

    public TestGenerationCoordinator(ContextReducer contextReducer,
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

    public AgentResult execute(MidasContext context, String agentName) {
        GoalKeeperValidator validator = validatorRegistry.getValidator(MidasState.TEST_GENERATION)
                .orElseThrow(() -> new IllegalStateException(
                        "No GoalKeeperValidator registered for TEST_GENERATION."));

        if (RemediationDirectiveSupport.isSurgicalPatch(context.getRemediationDirective())) {
            return executeSurgicalTestPatch(context, agentName, validator);
        }
        return executeFullGeneration(context, agentName, validator);
    }

    private AgentResult executeSurgicalTestPatch(MidasContext context,
                                                 String agentName,
                                                 GoalKeeperValidator validator) {
        List<String> affectedPaths = RemediationDirectiveSupport.affectedPaths(context.getRemediationDirective());
        JsonNode baselineTests = context.getGeneratedTests();
        JsonNode patchedSource = context.getGeneratedSourceCode();

        if (affectedPaths.isEmpty() || patchedSource == null || patchedSource.isNull()) {
            throw new IllegalStateException("Surgical test patch requires affected_paths and patched source.");
        }

        if (HybridExecutionModel.isHybrid(context)) {
            return executeHybridSurgicalTestPatch(context, agentName, validator, affectedPaths, baselineTests, patchedSource);
        }

        PatchAttemptResult patchResult = executeTestPatchPass(
                context, null, affectedPaths, patchedSource, agentName, validator);
        JsonNode mergedTests = mergeTestDelta(baselineTests, patchResult.patchTests());
        String mergedJson = serializeTestMap(mergedTests);
        validator.validate(mergedJson);
        return new AgentResult(mergedTests, mergedJson, patchResult.attemptsUsed(),
                patchResult.promptTokens(), patchResult.completionTokens(), patchResult.modelId());
    }

    private AgentResult executeHybridSurgicalTestPatch(MidasContext context,
                                                       String agentName,
                                                       GoalKeeperValidator validator,
                                                       List<String> affectedPaths,
                                                       JsonNode baselineTests,
                                                       JsonNode patchedSource) {
        List<String> clientPaths = filterPathsForSurface(affectedPaths, ImplementationSurface.CLIENT);
        List<String> serverPaths = filterPathsForSurface(affectedPaths, ImplementationSurface.SERVER);

        JsonNode mergedTests = baselineTests != null && baselineTests.isObject()
                ? baselineTests.deepCopy()
                : objectMapper.createObjectNode();
        int totalAttempts = 0;
        int totalPromptTokens = 0;
        int totalCompletionTokens = 0;
        String modelId = "";

        if (!clientPaths.isEmpty()) {
            PatchAttemptResult clientPatch = executeTestPatchPass(
                    context, ImplementationSurface.CLIENT, clientPaths, patchedSource, agentName + "Client", validator);
            mergedTests = mergeTestDelta(mergedTests, clientPatch.patchTests());
            totalAttempts += clientPatch.attemptsUsed();
            totalPromptTokens += clientPatch.promptTokens();
            totalCompletionTokens += clientPatch.completionTokens();
            modelId = clientPatch.modelId();
        }
        if (!serverPaths.isEmpty()) {
            PatchAttemptResult serverPatch = executeTestPatchPass(
                    context, ImplementationSurface.SERVER, serverPaths, patchedSource, agentName + "Server", validator);
            mergedTests = mergeTestDelta(mergedTests, serverPatch.patchTests());
            totalAttempts += serverPatch.attemptsUsed();
            totalPromptTokens += serverPatch.promptTokens();
            totalCompletionTokens += serverPatch.completionTokens();
            if (modelId.isBlank()) {
                modelId = serverPatch.modelId();
            }
        }

        if (totalAttempts == 0) {
            throw new IllegalStateException("No HYBRID surface matched affected_paths for surgical test patch.");
        }

        String mergedJson = serializeTestMap(mergedTests);
        validator.validate(mergedJson);
        return new AgentResult(mergedTests, mergedJson, totalAttempts, totalPromptTokens, totalCompletionTokens, modelId);
    }

    private PatchAttemptResult executeTestPatchPass(MidasContext context,
                                                    ImplementationSurface surface,
                                                    List<String> pathsForPass,
                                                    JsonNode patchedSource,
                                                    String llmAgentName,
                                                    GoalKeeperValidator validator) {
        AgentContextView view = surface == null
                ? contextReducer.reducePatchTestPass(context, pathsForPass, patchedSource)
                : contextReducer.reducePatchTestPass(context, pathsForPass, patchedSource, surface);

        String baseUserMessage = buildPatchUserMessage(view, surface);
        String modelOverride = llmModelPolicy.resolve(MidasState.TEST_GENERATION);
        QaEngineerValidator qaValidator = requireQaValidator(validator);
        String lastError = null;
        int totalPromptTokens = 0;
        int totalCompletionTokens = 0;

        for (int attempt = 1; attempt <= MAX_PASS_RETRIES; attempt++) {
            String userMessage = attempt == 1
                    ? baseUserMessage
                    : injectCorrectionFeedback(baseUserMessage, lastError, attempt);

            log.info("[TestGenerationCoordinator] PATCH {} attempt {}/{} — run=[{}]",
                    surface != null ? surface.name() : "SINGLE",
                    attempt, MAX_PASS_RETRIES, context.getPipelineRunId());

            try {
                LlmCallResult llmResult = invokeLlm(
                        context, llmAgentName, LlmCallRequest.of(
                        MidasState.TEST_GENERATION,
                        llmAgentName,
                        effectivePatchSystemPrompt(context),
                        userMessage,
                        context.getPipelineRunId(),
                        modelOverride),
                        attempt, MAX_PASS_RETRIES);
                totalPromptTokens += llmResult.promptTokens();
                totalCompletionTokens += llmResult.completionTokens();
                String sanitized = JsonSanitizer.sanitize(llmResult.text());
                JsonNode patchTests = qaValidator.validatePatchDelta(sanitized);
                return new PatchAttemptResult(patchTests, attempt, totalPromptTokens, totalCompletionTokens,
                        llmResult.modelUsed());

            } catch (ValidationHookException e) {
                lastError = AgentRetryPolicy.formatViolationsForFeedback(e);
                log.warn("[TestGenerationCoordinator] PATCH attempt {}/{} rejected: {}",
                        attempt, MAX_PASS_RETRIES, lastError);
                if (!AgentRetryPolicy.canRetry(e, attempt)) {
                    break;
                }
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
                llmAgentName,
                ContextReducer.AgentRole.QA_ENGINEER,
                MAX_PASS_RETRIES,
                "Surgical test patch failed: " + lastError);
    }

    private JsonNode mergeTestDelta(JsonNode baselineTests, JsonNode deltaTests) {
        ObjectNode merged = baselineTests != null && baselineTests.isObject()
                ? baselineTests.deepCopy()
                : objectMapper.createObjectNode();
        if (deltaTests == null || !deltaTests.isObject() || deltaTests.isEmpty()) {
            throw new IllegalStateException("Surgical test patch produced an empty delta map.");
        }
        deltaTests.fields().forEachRemaining(entry -> merged.set(entry.getKey(), entry.getValue()));
        return merged;
    }

    private String serializeTestMap(JsonNode tests) {
        try {
            return objectMapper.writeValueAsString(tests);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize merged test map.", e);
        }
    }

    private AgentResult executeFullGeneration(MidasContext context, String agentName, GoalKeeperValidator validator) {

        if (HybridExecutionModel.isHybrid(context)) {
            return executeHybridFanOut(context, agentName, validator);
        }
        return HybridExecutionModel.singlePassSurface(context)
                .map(surface -> executeSurfaceSinglePass(context, agentName, surface, validator))
                .orElseGet(() -> executeSinglePass(
                        context,
                        agentName,
                        AgentSystemPrompts.QA_ENGINEER_PROMPT,
                        contextReducer.reduce(context, ContextReducer.AgentRole.QA_ENGINEER),
                        validator,
                        agentName));
    }

    private AgentResult executeSurfaceSinglePass(MidasContext context,
                                                 String agentName,
                                                 ImplementationSurface surface,
                                                 GoalKeeperValidator validator) {
        String systemPrompt = surface == ImplementationSurface.CLIENT
                ? AgentSystemPrompts.HYBRID_CLIENT_QA_PROMPT
                : AgentSystemPrompts.HYBRID_SERVER_QA_PROMPT;
        PassResult pass = executePass(context, surface, systemPrompt, agentName, validator);
        String json;
        try {
            json = objectMapper.writeValueAsString(pass.validated());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize " + surface + " test map.", e);
        }
        return new AgentResult(pass.validated(), json, pass.attemptsUsed(),
                pass.promptTokens(), pass.completionTokens(), pass.modelId());
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
        AgentContextView view = contextReducer.reduceTestGenerationPass(context, surface);
        String baseUserMessage = buildUserMessage(view, surface);
        String modelOverride = llmModelPolicy.resolve(MidasState.TEST_GENERATION);
        String lastError = null;
        int totalPromptTokens = 0;
        int totalCompletionTokens = 0;

        for (int attempt = 1; attempt <= MAX_PASS_RETRIES; attempt++) {
            String userMessage = attempt == 1
                    ? baseUserMessage
                    : injectCorrectionFeedback(baseUserMessage, lastError, attempt);

            log.info("[TestGenerationCoordinator] {} pass attempt {}/{} — run=[{}]",
                    surface, attempt, MAX_PASS_RETRIES, context.getPipelineRunId());

            try {
                LlmCallResult llmResult = invokeLlm(
                        context, llmAgentName, LlmCallRequest.of(
                        MidasState.TEST_GENERATION,
                        llmAgentName,
                        effectiveSystemPrompt(context, systemPrompt),
                        userMessage,
                        context.getPipelineRunId(),
                        modelOverride),
                        attempt, MAX_PASS_RETRIES);
                totalPromptTokens += llmResult.promptTokens();
                totalCompletionTokens += llmResult.completionTokens();
                String raw = llmResult.text();

                String sanitized = JsonSanitizer.sanitize(raw);
                JsonNode validated = validator.validate(sanitized);
                return new PassResult(validated, attempt, totalPromptTokens, totalCompletionTokens, llmResult.modelUsed());

            } catch (ValidationHookException e) {
                lastError = AgentRetryPolicy.formatViolationsForFeedback(e);
                log.warn("[TestGenerationCoordinator] {} pass attempt {}/{} rejected: {}",
                        surface, attempt, MAX_PASS_RETRIES, lastError);
                if (!AgentRetryPolicy.canRetry(e, attempt)) {
                    break;
                }
                backoffBeforeRetry(attempt);

            } catch (LlmCallException e) {
                if (!e.isRetryable()) {
                    throw e;
                }
                lastError = "LLM transport error: " + e.getMessage();
                log.warn("[TestGenerationCoordinator] {} pass attempt {}/{} transport error: {}",
                        surface, attempt, MAX_PASS_RETRIES, e.getMessage());
                backoffBeforeRetry(attempt);
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
        String modelOverride = llmModelPolicy.resolve(MidasState.TEST_GENERATION);
        String lastError = null;
        int totalPromptTokens = 0;
        int totalCompletionTokens = 0;

        for (int attempt = 1; attempt <= MAX_PASS_RETRIES; attempt++) {
            String userMessage = attempt == 1
                    ? baseUserMessage
                    : injectCorrectionFeedback(baseUserMessage, lastError, attempt);

            log.info("[TestGenerationCoordinator] Single pass attempt {}/{} — run=[{}]",
                    attempt, MAX_PASS_RETRIES, context.getPipelineRunId());

            try {
                LlmCallResult llmResult = invokeLlm(
                        context, llmAgentName, LlmCallRequest.of(
                        MidasState.TEST_GENERATION,
                        llmAgentName,
                        effectiveSystemPrompt(context, systemPrompt),
                        userMessage,
                        context.getPipelineRunId(),
                        modelOverride),
                        attempt, MAX_PASS_RETRIES);
                totalPromptTokens += llmResult.promptTokens();
                totalCompletionTokens += llmResult.completionTokens();
                String raw = llmResult.text();

                String sanitized = JsonSanitizer.sanitize(raw);
                JsonNode validated = validator.validate(sanitized);
                return new AgentResult(validated, sanitized, attempt, totalPromptTokens, totalCompletionTokens,
                        llmResult.modelUsed(), llmResult.finishReason());

            } catch (ValidationHookException e) {
                lastError = AgentRetryPolicy.formatViolationsForFeedback(e);
                log.warn("[TestGenerationCoordinator] Single pass attempt {}/{} rejected: {}",
                        attempt, MAX_PASS_RETRIES, lastError);
                if (!AgentRetryPolicy.canRetry(e, attempt)) {
                    break;
                }
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
                ContextReducer.AgentRole.QA_ENGINEER,
                MAX_PASS_RETRIES,
                lastError);
    }

    private String effectiveSystemPrompt(MidasContext context, String baseSystemPrompt) {
        return AgentSystemPrompts.appendProductReviewRemediation(
                baseSystemPrompt, context.getRemediationDirective());
    }

    private String effectivePatchSystemPrompt(MidasContext context) {
        return AgentSystemPrompts.appendProductReviewRemediation(
                AgentSystemPrompts.QA_PATCH_PROMPT, context.getRemediationDirective());
    }

    String buildPatchUserMessage(AgentContextView view, ImplementationSurface surface) {
        StringBuilder sb = new StringBuilder();
        sb.append("USER IDEA:\n").append(view.getRawUserIdea()).append("\n\n");

        if (surface != null) {
            sb.append("SURGICAL TEST PATCH PASS: ")
              .append(surface.name())
              .append(" — write delta tests ONLY for affected paths on the ")
              .append(surface == ImplementationSurface.CLIENT ? "client" : "server")
              .append(" surface.\n\n");
        } else {
            sb.append("SURGICAL TEST PATCH PASS — write delta tests ONLY for the affected source paths.\n\n");
        }

        Map<String, JsonNode> artifacts = view.safeArtifacts();
        if (!artifacts.isEmpty()) {
            sb.append("UPSTREAM ARTIFACTS:\n");
            artifacts.forEach((key, node) ->
                    sb.append("## ").append(key).append("\n")
                      .append(node.toPrettyString()).append("\n\n"));
        }

        sb.append("TASK: Produce the surgical test patch output. Follow the system prompt JSON schema exactly.");
        return sb.toString();
    }

    private static List<String> filterPathsForSurface(List<String> paths, ImplementationSurface surface) {
        List<String> filtered = new ArrayList<>();
        for (String path : paths) {
            boolean include = surface == ImplementationSurface.CLIENT
                    ? ArchitectureSurfaceSlicer.isClientPath(path)
                    : ArchitectureSurfaceSlicer.isServerPath(path);
            if (include) {
                filtered.add(path);
            }
        }
        return filtered;
    }

    private static QaEngineerValidator requireQaValidator(GoalKeeperValidator validator) {
        if (!(validator instanceof QaEngineerValidator qaValidator)) {
            throw new IllegalStateException("TEST_GENERATION requires QaEngineerValidator.");
        }
        return qaValidator;
    }

    private record PatchAttemptResult(JsonNode patchTests, int attemptsUsed, int promptTokens, int completionTokens, String modelId) {}

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
                + AgentRetryPolicy.formatViolationsForFeedback(error) + "\n"
                + "Fix ALL violations. Output ONLY the corrected JSON object.";
    }

    private LlmCallResult invokeLlm(MidasContext context,
                                    String llmAgentName,
                                    LlmCallRequest request,
                                    int attempt,
                                    int maxAttempts) throws LlmCallException {
        LlmCallResult result = llmClient.call(request);
        LlmCallObservability.logCallMetadata(
                context.getPipelineRunId(), llmAgentName, attempt, maxAttempts, result);
        LlmCallObservability.logFinOps(
                context.getPipelineRunId(), llmAgentName, result.modelUsed(),
                result.promptTokens(), result.completionTokens());
        AgentRetryPolicy.failFastIfTruncated(result, llmAgentName, context.getPipelineRunId());
        return result;
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

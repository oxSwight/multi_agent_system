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
import com.midas.d3.validation.ImplementationEngineerValidator;
import com.midas.d3.validation.ImplementationOutputUnwrapper;
import com.midas.d3.validation.ValidationHookException;
import com.midas.d3.statemachine.remediation.RemediationDirectiveSupport;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
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

        if (RemediationDirectiveSupport.isSurgicalPatch(context.getRemediationDirective())) {
            return executeSurgicalPatchWithFallback(context, agentName, validator);
        }
        return executeFullGeneration(context, agentName, validator);
    }

    private AgentResult executeSurgicalPatchWithFallback(MidasContext context,
                                                         String agentName,
                                                         GoalKeeperValidator validator) {
        try {
            return executeSurgicalPatch(context, agentName, validator);
        } catch (PatchFallbackException e) {
            log.warn("[CodeGenerationCoordinator] Surgical patch failed for run [{}] — {}. Falling back to full regeneration.",
                    context.getPipelineRunId(), e.getMessage());
            return executeFullGeneration(context, agentName, validator);
        }
    }

    private AgentResult executeSurgicalPatch(MidasContext context,
                                             String agentName,
                                             GoalKeeperValidator validator) {
        List<String> affectedPaths = RemediationDirectiveSupport.affectedPaths(context.getRemediationDirective());
        JsonNode baselineSource = context.getGeneratedSourceCode();
        JsonNode baselineManifest = context.getFeatureManifest();

        if (affectedPaths.isEmpty() || baselineSource == null || baselineSource.isNull()
                || baselineManifest == null || baselineManifest.isNull()) {
            throw new PatchFallbackException("Surgical patch prerequisites missing — affected paths or baseline artifacts absent.");
        }

        if (HybridExecutionModel.isHybrid(context)) {
            return executeHybridSurgicalPatch(context, agentName, validator, affectedPaths, baselineSource, baselineManifest);
        }

        PatchAttemptResult patchResult = executePatchPass(
                context,
                null,
                affectedPaths,
                agentName,
                validator);
        JsonNode mergedSource = applySourcePatch(baselineSource, patchResult.patchSourceFiles());
        JsonNode envelope = validateMergedPatchEnvelope(mergedSource, baselineManifest, context, validator);
        String json = serializeEnvelope(envelope);
        return new AgentResult(envelope, json, patchResult.attemptsUsed(),
                patchResult.promptTokens(), patchResult.completionTokens(), patchResult.modelId());
    }

    private AgentResult executeHybridSurgicalPatch(MidasContext context,
                                                   String agentName,
                                                   GoalKeeperValidator validator,
                                                   List<String> affectedPaths,
                                                   JsonNode baselineSource,
                                                   JsonNode baselineManifest) {
        List<String> clientPaths = filterPathsForSurface(affectedPaths, ImplementationSurface.CLIENT);
        List<String> serverPaths = filterPathsForSurface(affectedPaths, ImplementationSurface.SERVER);

        if (clientPaths.isEmpty() && serverPaths.isEmpty()) {
            throw new PatchFallbackException("No affected paths resolved to a HYBRID surface.");
        }

        JsonNode mergedSource = baselineSource.deepCopy();
        int totalAttempts = 0;
        int totalPromptTokens = 0;
        int totalCompletionTokens = 0;
        String modelId = "";

        if (!clientPaths.isEmpty()) {
            PatchAttemptResult clientPatch = executePatchPass(
                    context, ImplementationSurface.CLIENT, clientPaths, agentName + "Client", validator);
            mergedSource = applySourcePatch(mergedSource, clientPatch.patchSourceFiles());
            totalAttempts += clientPatch.attemptsUsed();
            totalPromptTokens += clientPatch.promptTokens();
            totalCompletionTokens += clientPatch.completionTokens();
            modelId = clientPatch.modelId();
        }
        if (!serverPaths.isEmpty()) {
            PatchAttemptResult serverPatch = executePatchPass(
                    context, ImplementationSurface.SERVER, serverPaths, agentName + "Server", validator);
            mergedSource = applySourcePatch(mergedSource, serverPatch.patchSourceFiles());
            totalAttempts += serverPatch.attemptsUsed();
            totalPromptTokens += serverPatch.promptTokens();
            totalCompletionTokens += serverPatch.completionTokens();
            if (modelId.isBlank()) {
                modelId = serverPatch.modelId();
            }
        }

        JsonNode envelope = validateMergedPatchEnvelope(mergedSource, baselineManifest, context, validator);
        String json = serializeEnvelope(envelope);
        return new AgentResult(envelope, json, totalAttempts, totalPromptTokens, totalCompletionTokens, modelId);
    }

    private PatchAttemptResult executePatchPass(MidasContext context,
                                                ImplementationSurface surface,
                                                List<String> pathsForPass,
                                                String llmAgentName,
                                                GoalKeeperValidator validator) {
        AgentContextView view = surface == null
                ? contextReducer.reducePatchImplementationPass(context, pathsForPass)
                : contextReducer.reducePatchImplementationPass(context, pathsForPass, surface);

        String baseUserMessage = buildPatchUserMessage(view, surface);
        String modelOverride = llmModelPolicy.resolve(MidasState.CODE_GENERATION);
        ImplementationEngineerValidator implValidator = requireImplementationValidator(validator);
        String lastError = null;
        int totalPromptTokens = 0;
        int totalCompletionTokens = 0;

        for (int attempt = 1; attempt <= MAX_PASS_RETRIES; attempt++) {
            String userMessage = attempt == 1
                    ? baseUserMessage
                    : injectCorrectionFeedback(baseUserMessage, lastError, attempt);

            log.info("[CodeGenerationCoordinator] PATCH {} attempt {}/{} — run=[{}]",
                    surface != null ? surface.name() : "SINGLE",
                    attempt, MAX_PASS_RETRIES, context.getPipelineRunId());

            try {
                LlmCallResult llmResult = llmClient.call(LlmCallRequest.of(
                        MidasState.CODE_GENERATION,
                        llmAgentName,
                        effectivePatchSystemPrompt(context),
                        userMessage,
                        context.getPipelineRunId(),
                        modelOverride));
                totalPromptTokens += llmResult.promptTokens();
                totalCompletionTokens += llmResult.completionTokens();
                String sanitized = JsonSanitizer.sanitize(llmResult.text());
                JsonNode patchSourceFiles = implValidator.validatePatchOutput(sanitized, pathsForPass);
                return new PatchAttemptResult(patchSourceFiles, attempt, totalPromptTokens, totalCompletionTokens,
                        llmResult.modelUsed());

            } catch (ValidationHookException e) {
                lastError = String.join(" | ", e.getViolations());
                log.warn("[CodeGenerationCoordinator] PATCH attempt {}/{} rejected: {}",
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

        throw new PatchFallbackException("Patch LLM pass exhausted retries: " + lastError);
    }

    private JsonNode applySourcePatch(JsonNode baseline, JsonNode patch) {
        try {
            return SourceMapPatcher.apply(baseline, patch, objectMapper);
        } catch (PatchValidationException e) {
            throw new PatchFallbackException(e.getMessage());
        }
    }

    private JsonNode validateMergedPatchEnvelope(JsonNode mergedSource,
                                                 JsonNode baselineManifest,
                                                 MidasContext context,
                                                 GoalKeeperValidator validator) {
        JsonNode envelope = buildEnvelope(mergedSource, baselineManifest);
        try {
            String json = objectMapper.writeValueAsString(envelope);
            return requireImplementationValidator(validator).validateWithTechnicalSpec(
                    json, context.getTechnicalSpec());
        } catch (JsonProcessingException e) {
            throw new PatchFallbackException("Failed to serialize merged patch envelope: " + e.getMessage());
        } catch (ValidationHookException e) {
            throw new PatchFallbackException("Merged patch envelope rejected: " + String.join(" | ", e.getViolations()));
        }
    }

    private String serializeEnvelope(JsonNode envelope) {
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize implementation envelope.", e);
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
        JsonNode envelope = buildEnvelope(pass.sourceFiles(), pass.featureManifest());
        String json;
        try {
            json = objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize " + surface + " implementation envelope.", e);
        }
        return new AgentResult(envelope, json, pass.attemptsUsed(),
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

        JsonNode mergedSources = ImplementationSourceMerger.merge(
                clientPass.sourceFiles(), serverPass.sourceFiles(), objectMapper);
        JsonNode mergedManifest = FeatureManifestMerger.merge(
                clientPass.featureManifest(), serverPass.featureManifest(), objectMapper);
        JsonNode envelope = buildEnvelope(mergedSources, mergedManifest);

        String mergedJson;
        try {
            mergedJson = objectMapper.writeValueAsString(envelope);
            requireImplementationValidator(validator).validateWithTechnicalSpec(
                    mergedJson, context.getTechnicalSpec());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize merged HYBRID implementation envelope.", e);
        }

        int totalAttempts = clientPass.attemptsUsed() + serverPass.attemptsUsed();
        log.info("[CodeGenerationCoordinator] HYBRID fan-out complete for run [{}] — {} client files, {} server files.",
                context.getPipelineRunId(),
                clientPass.sourceFiles().size(),
                serverPass.sourceFiles().size());

        return new AgentResult(envelope, mergedJson, totalAttempts,
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
                JsonNode envelope;
                if (HybridExecutionModel.isHybrid(context) && surface != null) {
                    // In HYBRID fan-out each pass contains only a surface-local partial manifest.
                    // Full core_features coverage is validated after client/server merge.
                    envelope = requireImplementationValidator(validator).validate(sanitized);
                } else {
                    envelope = requireImplementationValidator(validator)
                            .validateWithTechnicalSpec(sanitized, context.getTechnicalSpec());
                }
                ImplementationOutputUnwrapper.UnwrappedEnvelope unwrapped =
                        ImplementationOutputUnwrapper.unwrap(envelope);
                return new PassResult(unwrapped.sourceFiles(), unwrapped.featureManifest(), attempt,
                        totalPromptTokens, totalCompletionTokens, llmResult.modelUsed());

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
                JsonNode envelope = requireImplementationValidator(validator)
                        .validateWithTechnicalSpec(sanitized, context.getTechnicalSpec());
                return new AgentResult(envelope, sanitized, attempt, totalPromptTokens, totalCompletionTokens, llmResult.modelUsed());

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

    private String effectivePatchSystemPrompt(MidasContext context) {
        return AgentSystemPrompts.appendProductReviewRemediation(
                AgentSystemPrompts.IMPLEMENTATION_PATCH_PROMPT, context.getRemediationDirective());
    }

    String buildPatchUserMessage(AgentContextView view, ImplementationSurface surface) {
        StringBuilder sb = new StringBuilder();
        sb.append("USER IDEA:\n").append(view.getRawUserIdea()).append("\n\n");

        if (surface != null) {
            sb.append("SURGICAL PATCH PASS: ")
              .append(surface.name())
              .append(" — patch ONLY affected paths on the ")
              .append(surface == ImplementationSurface.CLIENT ? "client" : "server")
              .append(" surface.\n\n");
        } else {
            sb.append("SURGICAL PATCH PASS — patch ONLY the affected paths listed in the remediation directive.\n\n");
        }

        Map<String, JsonNode> artifacts = view.safeArtifacts();
        if (!artifacts.isEmpty()) {
            sb.append("UPSTREAM ARTIFACTS:\n");
            artifacts.forEach((key, node) ->
                    sb.append("## ").append(key).append("\n")
                      .append(node.toPrettyString()).append("\n\n"));
        }

        sb.append("TASK: Produce the surgical patch output. Follow the system prompt JSON schema exactly.");
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

    private static final class PatchFallbackException extends RuntimeException {
        PatchFallbackException(String message) {
            super(message);
        }
    }

    private record PatchAttemptResult(JsonNode patchSourceFiles, int attemptsUsed,
                                      int promptTokens, int completionTokens, String modelId) {}

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

    private JsonNode buildEnvelope(JsonNode sourceFiles, JsonNode featureManifest) {
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.set("source_files", sourceFiles);
        envelope.set("feature_manifest", featureManifest);
        return envelope;
    }

    private static ImplementationEngineerValidator requireImplementationValidator(GoalKeeperValidator validator) {
        if (!(validator instanceof ImplementationEngineerValidator implValidator)) {
            throw new IllegalStateException("CODE_GENERATION requires ImplementationEngineerValidator.");
        }
        return implValidator;
    }

    private record PassResult(JsonNode sourceFiles, JsonNode featureManifest, int attemptsUsed,
                              int promptTokens, int completionTokens, String modelId) {}
}

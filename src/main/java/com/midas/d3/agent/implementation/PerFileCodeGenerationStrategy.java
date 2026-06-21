package com.midas.d3.agent.implementation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.midas.d3.agent.AgentRetryPolicy;
import com.midas.d3.agent.AgentSystemPrompts;
import com.midas.d3.agent.base.AgentExecutionException;
import com.midas.d3.context.AgentContextView;
import com.midas.d3.context.ContextReducer;
import com.midas.d3.context.MidasContext;
import com.midas.d3.llm.LlmCallException;
import com.midas.d3.llm.LlmCallObservability;
import com.midas.d3.llm.LlmCallRequest;
import com.midas.d3.llm.LlmCallResult;
import com.midas.d3.llm.LlmClient;
import com.midas.d3.llm.LlmModelPolicy;
import com.midas.d3.sanitizer.JsonSanitizer;
import com.midas.d3.statemachine.MidasState;
import com.midas.d3.validation.ImplementationEngineerValidator;
import com.midas.d3.validation.ValidationHookException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Iterates {@code architectureDesign.file_layout} and generates one file per LLM call,
 * then assembles the implementation envelope for final validation.
 */
@Slf4j
@Component
public class PerFileCodeGenerationStrategy {

    static final int MAX_PASS_RETRIES = AgentRetryPolicy.maxValidationAttempts();

    private final LlmClient llmClient;
    private final LlmModelPolicy llmModelPolicy;
    private final ObjectMapper objectMapper;

    public PerFileCodeGenerationStrategy(LlmClient llmClient,
                                         LlmModelPolicy llmModelPolicy,
                                         ObjectMapper objectMapper) {
        this.llmClient = llmClient;
        this.llmModelPolicy = llmModelPolicy;
        this.objectMapper = objectMapper;
    }

    public PassResult generatePass(MidasContext context,
                                   AgentContextView view,
                                   ImplementationSurface surface,
                                   String baseSystemPrompt,
                                   String llmAgentName,
                                   ImplementationEngineerValidator validator,
                                   boolean hybridPartialPass) {
        List<String> filePaths = resolveFileLayout(view);
        if (filePaths.isEmpty()) {
            throw new AgentExecutionException(
                    llmAgentName,
                    ContextReducer.AgentRole.IMPLEMENTATION_ENGINEER,
                    0,
                    "architectureDesign.file_layout is empty or missing — cannot run per-file generation.");
        }

        String systemPrompt = effectiveSystemPrompt(context, baseSystemPrompt);
        String modelOverride = llmModelPolicy.resolve(MidasState.CODE_GENERATION);
        ObjectNode sourceFiles = objectMapper.createObjectNode();

        int totalAttempts = 0;
        int totalPromptTokens = 0;
        int totalCompletionTokens = 0;
        String modelId = "";
        String lastFinishReason = "";

        log.info("[PerFileCodeGenerationStrategy] {} generating {} file(s) for run [{}].",
                surface != null ? surface.name() + " pass" : "Single pass",
                filePaths.size(), context.getPipelineRunId());

        for (int i = 0; i < filePaths.size(); i++) {
            String path = filePaths.get(i);
            FileGenerationResult fileResult = generateSingleFile(
                    context, view, surface, path, sourceFiles, filePaths, i + 1, filePaths.size(),
                    systemPrompt, llmAgentName, modelOverride, validator);

            sourceFiles.put(path, fileResult.content());
            totalAttempts += fileResult.attemptsUsed();
            totalPromptTokens += fileResult.promptTokens();
            totalCompletionTokens += fileResult.completionTokens();
            modelId = fileResult.modelId();
            lastFinishReason = fileResult.finishReason();
        }

        JsonNode featureManifest = FeatureManifestBuilder.build(
                context.getTechnicalSpec(), sourceFiles, objectMapper, hybridPartialPass, surface);
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.set("source_files", sourceFiles);
        envelope.set("feature_manifest", featureManifest);

        try {
            String json = objectMapper.writeValueAsString(envelope);
            if (hybridPartialPass) {
                validator.validate(json);
            } else {
                validator.validateWithTechnicalSpec(json, context.getTechnicalSpec());
            }
        } catch (ValidationHookException e) {
            throw new AgentExecutionException(
                    llmAgentName,
                    ContextReducer.AgentRole.IMPLEMENTATION_ENGINEER,
                    totalAttempts,
                    "Assembled envelope rejected after per-file generation: "
                            + AgentRetryPolicy.formatViolationsForFeedback(e));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize assembled implementation envelope.", e);
        }

        LlmCallObservability.logExecutionSummary(
                context.getPipelineRunId(), llmAgentName,
                totalPromptTokens, totalCompletionTokens, lastFinishReason);
        return new PassResult(sourceFiles, featureManifest, totalAttempts,
                totalPromptTokens, totalCompletionTokens, modelId);
    }

    static List<String> resolveFileLayout(AgentContextView view) {
        Map<String, JsonNode> artifacts = view.safeArtifacts();
        JsonNode architecture = artifacts.get("architectureDesign");
        if (architecture == null || !architecture.isObject()) {
            return List.of();
        }
        JsonNode layout = architecture.get("file_layout");
        if (layout == null || !layout.isArray()) {
            return List.of();
        }
        List<String> paths = new ArrayList<>();
        for (JsonNode entry : layout) {
            if (entry.isTextual() && !entry.asText().isBlank()) {
                paths.add(entry.asText().strip());
            }
        }
        return paths;
    }

    private FileGenerationResult generateSingleFile(MidasContext context,
                                                    AgentContextView view,
                                                    ImplementationSurface surface,
                                                    String targetPath,
                                                    ObjectNode alreadyGenerated,
                                                    List<String> allPaths,
                                                    int fileIndex,
                                                    int totalFiles,
                                                    String systemPrompt,
                                                    String llmAgentName,
                                                    String modelOverride,
                                                    ImplementationEngineerValidator validator) {
        String baseUserMessage = buildPerFileUserMessage(
                view, surface, targetPath, alreadyGenerated, allPaths, fileIndex, totalFiles);
        String lastError = null;
        int totalPromptTokens = 0;
        int totalCompletionTokens = 0;
        String lastFinishReason = "";
        int effectiveMax = AgentRetryPolicy.maxValidationAttempts();

        for (int attempt = 1; attempt <= AgentRetryPolicy.maxValidationAttempts(); attempt++) {
            String userMessage = attempt == 1
                    ? baseUserMessage
                    : injectCorrectionFeedback(baseUserMessage, lastError, attempt, effectiveMax);

            log.info("[PerFileCodeGenerationStrategy] {} file [{}/{}] {} attempt {}/{} — run=[{}]",
                    surface != null ? surface.name() : "SINGLE",
                    fileIndex, totalFiles, targetPath,
                    attempt, effectiveMax, context.getPipelineRunId());

            try {
                LlmCallResult llmResult = invokeLlm(context, llmAgentName, LlmCallRequest.of(
                        MidasState.CODE_GENERATION,
                        llmAgentName,
                        systemPrompt,
                        userMessage,
                        context.getPipelineRunId(),
                        modelOverride), attempt, effectiveMax);

                totalPromptTokens += llmResult.promptTokens();
                totalCompletionTokens += llmResult.completionTokens();
                lastFinishReason = llmResult.finishReason();
                String sanitized = JsonSanitizer.sanitize(llmResult.text());
                SingleFileLLMResponse parsed = validator.validateSingleFileOutput(sanitized, targetPath);
                return new FileGenerationResult(parsed.content(), attempt,
                        totalPromptTokens, totalCompletionTokens, llmResult.modelUsed(), lastFinishReason);

            } catch (ValidationHookException e) {
                effectiveMax = AgentRetryPolicy.maxAttemptsFor(e);
                lastError = AgentRetryPolicy.formatViolationsForFeedback(e);
                log.warn("[PerFileCodeGenerationStrategy] file [{}] attempt {}/{} rejected: {}",
                        targetPath, attempt, effectiveMax, lastError);
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

        LlmCallObservability.logExecutionSummary(
                context.getPipelineRunId(), llmAgentName,
                totalPromptTokens, totalCompletionTokens, lastFinishReason);
        throw new AgentExecutionException(
                llmAgentName,
                ContextReducer.AgentRole.IMPLEMENTATION_ENGINEER,
                effectiveMax,
                "Per-file generation failed for [" + targetPath + "]: " + lastError);
    }

    static String buildPerFileUserMessage(AgentContextView view,
                                          ImplementationSurface surface,
                                          String targetPath,
                                          ObjectNode alreadyGenerated,
                                          List<String> allPaths,
                                          int fileIndex,
                                          int totalFiles) {
        StringBuilder sb = new StringBuilder();
        sb.append("USER IDEA:\n").append(view.getRawUserIdea()).append("\n\n");

        if (surface != null) {
            sb.append("IMPLEMENTATION PASS: ")
              .append(surface.name())
              .append(" — generate ONLY the client/server file listed in TARGET FILE.\n\n");
        }

        Map<String, JsonNode> artifacts = view.safeArtifacts();
        if (!artifacts.isEmpty()) {
            sb.append("UPSTREAM ARTIFACTS:\n");
            artifacts.forEach((key, node) ->
                    sb.append("## ").append(key).append("\n")
                      .append(node.toPrettyString()).append("\n\n"));
        }

        sb.append("FILE GENERATION PROGRESS: ").append(fileIndex).append(" of ").append(totalFiles).append("\n");
        sb.append("FULL FILE LAYOUT (do not regenerate other paths in this response):\n");
        for (String path : allPaths) {
            sb.append("  - ").append(path);
            if (alreadyGenerated.has(path)) {
                sb.append(" [already generated]");
            }
            sb.append("\n");
        }
        sb.append("\n");

        if (!alreadyGenerated.isEmpty()) {
            sb.append("ALREADY GENERATED FILES (for import/reference consistency only):\n");
            alreadyGenerated.fields().forEachRemaining(entry ->
                    sb.append("## ").append(entry.getKey()).append("\n")
                      .append(entry.getValue().asText()).append("\n\n"));
        }

        sb.append("TARGET FILE: ").append(targetPath).append("\n\n");
        sb.append("TASK: Generate ONLY the TARGET FILE. Output the single-file JSON schema from the system prompt.");
        return sb.toString();
    }

    private static String effectiveSystemPrompt(MidasContext context, String baseSystemPrompt) {
        return AgentSystemPrompts.appendProductReviewRemediation(baseSystemPrompt, context.getRemediationDirective());
    }

    private static String injectCorrectionFeedback(String baseUserMessage, String error, int attempt, int maxAttempts) {
        return baseUserMessage
                + "\n\n"
                + "--- CORRECTION REQUIRED (attempt " + attempt + " of " + maxAttempts + ") ---\n"
                + "Your previous response was rejected by the schema validator.\n"
                + "Violations found:\n"
                + AgentRetryPolicy.formatViolationsForFeedback(error) + "\n"
                + "Fix ALL violations. Output ONLY the corrected single-file JSON object.";
    }

    private LlmCallResult invokeLlm(MidasContext context,
                                      String llmAgentName,
                                      LlmCallRequest request,
                                      int attempt,
                                      int maxAttempts) throws LlmCallException {
        LlmCallResult result = llmClient.call(request);
        LlmCallObservability.logTelemetry(
                context.getPipelineRunId(), llmAgentName, attempt, maxAttempts, result);
        AgentRetryPolicy.failFastIfTruncated(result, llmAgentName, context.getPipelineRunId());
        return result;
    }

    private static void backoffBeforeRetry(int attempt) {
        if (attempt >= MAX_PASS_RETRIES) {
            return;
        }
        try {
            Thread.sleep(3_000L * attempt);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    public record PassResult(JsonNode sourceFiles,
                             JsonNode featureManifest,
                             int attemptsUsed,
                             int promptTokens,
                             int completionTokens,
                             String modelId) {}

    private record FileGenerationResult(String content,
                                          int attemptsUsed,
                                          int promptTokens,
                                          int completionTokens,
                                          String modelId,
                                          String finishReason) {}
}

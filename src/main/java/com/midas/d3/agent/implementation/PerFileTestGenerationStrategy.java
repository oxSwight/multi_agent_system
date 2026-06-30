package com.midas.d3.agent.implementation;

import com.fasterxml.jackson.core.JsonProcessingException;
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
import com.midas.d3.statemachine.MidasState;
import com.midas.d3.validation.QaEngineerValidator;
import com.midas.d3.validation.ValidationHookException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Iterates test paths from {@code architectureDesign.file_layout} and generates one test file per LLM call,
 * then assembles the test map for final validation.
 */
@Slf4j
@Component
public class PerFileTestGenerationStrategy {

    static final int MAX_PASS_RETRIES = AgentRetryPolicy.maxValidationAttempts();

    private final LlmClient llmClient;
    private final LlmModelPolicy llmModelPolicy;
    private final ObjectMapper objectMapper;

    public PerFileTestGenerationStrategy(LlmClient llmClient,
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
                                   QaEngineerValidator validator) {
        List<String> testPaths = resolveTestFileLayout(view);
        if (testPaths.isEmpty()) {
            throw new AgentExecutionException(
                    llmAgentName,
                    ContextReducer.AgentRole.QA_ENGINEER,
                    0,
                    "architectureDesign.file_layout has no test file paths — cannot run per-file test generation.");
        }

        String systemPrompt = effectiveSystemPrompt(context, baseSystemPrompt);
        String modelOverride = llmModelPolicy.resolve(MidasState.TEST_GENERATION);
        ObjectNode testFiles = objectMapper.createObjectNode();

        int totalAttempts = 0;
        int totalPromptTokens = 0;
        int totalCompletionTokens = 0;
        String modelId = "";
        String lastFinishReason = "";

        log.info("[PerFileTestGenerationStrategy] {} generating {} test file(s) for run [{}].",
                surface != null ? surface.name() + " pass" : "Single pass",
                testPaths.size(), context.getPipelineRunId());

        for (int i = 0; i < testPaths.size(); i++) {
            String path = testPaths.get(i);
            TestFileGenerationResult fileResult = generateSingleTestFile(
                    context, view, surface, path, testFiles, testPaths, i + 1, testPaths.size(),
                    systemPrompt, llmAgentName, modelOverride, validator);

            testFiles.put(path, fileResult.content());
            totalAttempts += fileResult.attemptsUsed();
            totalPromptTokens += fileResult.promptTokens();
            totalCompletionTokens += fileResult.completionTokens();
            modelId = fileResult.modelId();
            lastFinishReason = fileResult.finishReason();
        }

        try {
            JsonNode generatedSource = view.safeArtifacts().get("generatedSourceCode");
            JsonNode architecture = view.safeArtifacts().get("architectureDesign");
            validator.validateWithGeneratedSource(
                    objectMapper.writeValueAsString(testFiles), generatedSource, architecture);
        } catch (ValidationHookException e) {
            throw new AgentExecutionException(
                    llmAgentName,
                    ContextReducer.AgentRole.QA_ENGINEER,
                    totalAttempts,
                    "Assembled test map rejected after per-file generation: "
                            + AgentRetryPolicy.formatViolationsForFeedback(e));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize assembled test map.", e);
        }

        LlmCallObservability.logExecutionSummary(
                context.getPipelineRunId(), llmAgentName,
                totalPromptTokens, totalCompletionTokens, lastFinishReason);
        return new PassResult(testFiles, totalAttempts, totalPromptTokens, totalCompletionTokens, modelId);
    }

    static List<String> resolveTestFileLayout(AgentContextView view) {
        Map<String, JsonNode> artifacts = view.safeArtifacts();
        JsonNode architecture = artifacts.get("architectureDesign");
        if (architecture == null || !architecture.isObject()) {
            return List.of();
        }
        JsonNode layout = architecture.get("file_layout");
        if (layout == null || !layout.isArray()) {
            return List.of();
        }
        List<String> explicit = new ArrayList<>();
        for (JsonNode entry : layout) {
            if (entry.isTextual() && !entry.asText().isBlank()) {
                String path = entry.asText().strip();
                if (isTestFilePath(path)) {
                    explicit.add(path);
                }
            }
        }
        if (!explicit.isEmpty()) {
            return explicit;
        }
        return deriveTestPathsFromLayout(layout);
    }

    static List<String> deriveTestPathsFromLayout(JsonNode layout) {
        List<String> derived = new ArrayList<>();
        for (JsonNode entry : layout) {
            if (!entry.isTextual() || entry.asText().isBlank()) {
                continue;
            }
            String path = entry.asText().strip();
            if (isTestFilePath(path) || shouldSkipForTestDerivation(path)) {
                continue;
            }
            String testPath = deriveTestPathForSource(path);
            if (testPath != null && !derived.contains(testPath)) {
                derived.add(testPath);
            }
        }
        return derived;
    }

    static boolean shouldSkipForTestDerivation(String path) {
        String lower = path.replace('\\', '/').toLowerCase(Locale.ROOT);
        return lower.endsWith("manifest.json")
                || lower.endsWith(".html")
                || lower.endsWith(".css")
                || lower.endsWith(".json");
    }

    static String deriveTestPathForSource(String sourcePath) {
        String normalized = sourcePath.replace('\\', '/');
        if (normalized.endsWith(".js")) {
            return normalized.substring(0, normalized.length() - 3) + ".test.js";
        }
        if (normalized.endsWith(".ts")) {
            return normalized.substring(0, normalized.length() - 3) + ".test.ts";
        }
        if (normalized.endsWith(".tsx")) {
            return normalized.substring(0, normalized.length() - 4) + ".test.tsx";
        }
        if (normalized.endsWith(".java") && normalized.contains("/main/")) {
            String name = normalized.substring(normalized.lastIndexOf('/') + 1);
            String baseName = name.substring(0, name.length() - 5);
            return normalized.replace("/main/", "/test/").replace(name, baseName + "Test.java");
        }
        if (normalized.endsWith(".java")) {
            int slash = normalized.lastIndexOf('/');
            String dir = slash >= 0 ? normalized.substring(0, slash + 1) : "";
            String name = slash >= 0 ? normalized.substring(slash + 1) : normalized;
            String baseName = name.substring(0, name.length() - 5);
            return dir + baseName + "Test.java";
        }
        return null;
    }

    static boolean isTestFilePath(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        String normalized = path.replace('\\', '/');
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (lower.contains("/test/")) {
            return true;
        }
        if (lower.contains("/__tests__/")) {
            return true;
        }
        if (lower.contains(".test.")) {
            return true;
        }
        if (lower.contains(".spec.")) {
            return true;
        }
        if (lower.contains("_test.")) {
            return true;
        }
        return normalized.endsWith("Test.java")
                || normalized.endsWith("IT.java")
                || normalized.endsWith("Spec.java");
    }

    private TestFileGenerationResult generateSingleTestFile(MidasContext context,
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
                                                            QaEngineerValidator validator) {
        String baseUserMessage = buildPerTestFileUserMessage(
                view, surface, targetPath, alreadyGenerated, allPaths, fileIndex, totalFiles);
        String lastError = null;
        int totalPromptTokens = 0;
        int totalCompletionTokens = 0;
        String lastFinishReason = "";
        int effectiveMax = AgentRetryPolicy.maxValidationAttempts();
        LlmCallRequest baseRequest = LlmCallRequest.of(
                MidasState.TEST_GENERATION, llmAgentName,
                systemPrompt, baseUserMessage,
                context.getPipelineRunId(), modelOverride);

        for (int attempt = 1; attempt <= AgentRetryPolicy.maxValidationAttempts(); attempt++) {
            LlmCallRequest request = attempt == 1
                    ? baseRequest
                    : baseRequest.withCorrectionFeedback(buildCorrectionFeedback(lastError, attempt, effectiveMax));

            log.info("[PerFileTestGenerationStrategy] {} test [{}/{}] {} attempt {}/{} — run=[{}]",
                    surface != null ? surface.name() : "SINGLE",
                    fileIndex, totalFiles, targetPath,
                    attempt, effectiveMax, context.getPipelineRunId());

            try {
                LlmCallResult llmResult = invokeLlm(context, llmAgentName, request, attempt, effectiveMax);

                totalPromptTokens += llmResult.promptTokens();
                totalCompletionTokens += llmResult.completionTokens();
                lastFinishReason = llmResult.finishReason();
                String content = validator.validateSingleFileOutput(llmResult.text(), targetPath);
                SingleTestLLMResponse parsed = new SingleTestLLMResponse(targetPath, content);
                return new TestFileGenerationResult(parsed.content(), attempt,
                        totalPromptTokens, totalCompletionTokens, llmResult.modelUsed(), lastFinishReason);

            } catch (ValidationHookException e) {
                effectiveMax = AgentRetryPolicy.maxAttemptsFor(e);
                lastError = AgentRetryPolicy.formatViolationsForFeedback(e);
                log.warn("[PerFileTestGenerationStrategy] test [{}] attempt {}/{} rejected: {}",
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
                ContextReducer.AgentRole.QA_ENGINEER,
                effectiveMax,
                "Per-file test generation failed for [" + targetPath + "]: " + lastError);
    }

    static String buildPerTestFileUserMessage(AgentContextView view,
                                              ImplementationSurface surface,
                                              String targetPath,
                                              ObjectNode alreadyGenerated,
                                              List<String> allPaths,
                                              int fileIndex,
                                              int totalFiles) {
        StringBuilder sb = new StringBuilder();
        sb.append("USER IDEA:\n").append(view.getRawUserIdea()).append("\n\n");

        if (surface != null) {
            sb.append("TEST GENERATION PASS: ")
              .append(surface.name())
              .append(" — generate ONLY the test file listed in TARGET TEST FILE.\n\n");
        }

        Map<String, JsonNode> artifacts = view.safeArtifacts();
        if (!artifacts.isEmpty()) {
            sb.append("UPSTREAM ARTIFACTS:\n");
            artifacts.forEach((key, node) ->
                    sb.append("## ").append(key).append("\n")
                      .append(node.toString()).append("\n\n"));
        }

        sb.append("TEST GENERATION PROGRESS: ").append(fileIndex).append(" of ").append(totalFiles).append("\n");
        sb.append("FULL TEST FILE LAYOUT (from architecture.file_layout — do not regenerate other paths in this response):\n");
        for (String path : allPaths) {
            sb.append("  - ").append(path);
            if (alreadyGenerated.has(path)) {
                sb.append(" [already generated]");
            }
            sb.append("\n");
        }
        sb.append("\n");

        if (!alreadyGenerated.isEmpty()) {
            // FinOps: collapse already-generated sibling tests to their public API (signatures/exports)
            // rather than re-sending full bodies — keeps per-file prompt growth linear, not O(N²).
            sb.append("ALREADY GENERATED TEST FILES (public API only — signatures for import/reference; bodies omitted):\n");
            alreadyGenerated.fields().forEachRemaining(entry ->
                    sb.append("## ").append(entry.getKey()).append("\n")
                      .append(SymbolTableExtractor.summarize(entry.getValue().asText(), entry.getKey())).append("\n\n"));
        }

        sb.append("TARGET TEST FILE: ").append(targetPath).append("\n\n");
        sb.append("TASK: Generate ONLY the TARGET TEST FILE. Use the full generatedSourceCode artifact above ")
          .append("to know what you are testing. Output ONLY the raw test source in a single markdown code block.");
        return sb.toString();
    }

    private static String effectiveSystemPrompt(MidasContext context, String baseSystemPrompt) {
        return AgentSystemPrompts.appendProductReviewRemediation(baseSystemPrompt, context.getRemediationDirective());
    }

    private static String buildCorrectionFeedback(String error, int attempt, int maxAttempts) {
        return "--- CORRECTION REQUIRED (attempt " + attempt + " of " + maxAttempts + ") ---\n"
                + "Your previous response was rejected by the validator.\n"
                + "Violations found:\n"
                + AgentRetryPolicy.formatViolationsForFeedback(error) + "\n"
                + "Fix ALL violations. Output ONLY the corrected raw test source in a single markdown code block.";
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

    public record PassResult(JsonNode validated,
                             int attemptsUsed,
                             int promptTokens,
                             int completionTokens,
                             String modelId) {}

    private record TestFileGenerationResult(String content,
                                            int attemptsUsed,
                                            int promptTokens,
                                            int completionTokens,
                                            String modelId,
                                            String finishReason) {}
}

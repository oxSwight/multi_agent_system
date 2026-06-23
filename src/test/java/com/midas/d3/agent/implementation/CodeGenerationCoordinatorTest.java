package com.midas.d3.agent.implementation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.midas.d3.agent.AgentSystemPrompts;
import com.midas.d3.agent.base.AgentResult;
import com.midas.d3.config.JacksonConfig;
import com.midas.d3.context.AgentContextView;
import com.midas.d3.context.ContextReducer;
import com.midas.d3.context.MidasContext;
import com.midas.d3.llm.LlmCallException;
import com.midas.d3.llm.LlmCallRequest;
import com.midas.d3.llm.LlmCallResult;
import com.midas.d3.llm.LlmClient;
import com.midas.d3.llm.LlmModelPolicy;
import com.midas.d3.statemachine.MidasState;
import com.midas.d3.statemachine.ValidatorRegistry;
import com.midas.d3.validation.FeatureManifestValidator;
import com.midas.d3.validation.ImplementationEngineerValidator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.atLeast;

@ExtendWith(MockitoExtension.class)
@DisplayName("CodeGenerationCoordinator")
class CodeGenerationCoordinatorTest {

    private static final String SERVER_FILE = "src/main/java/com/example/App.java";
    private static final String CLIENT_FILES = "manifest.json,src/popup.ts";
    private static final String CLI_FILE = "cmd/main.go";

    @Mock private ContextReducer contextReducer;
    @Mock private LlmClient llmClient;
    @Mock private LlmModelPolicy llmModelPolicy;
    @Mock private ValidatorRegistry validatorRegistry;

    private ObjectMapper objectMapper;
    private ImplementationEngineerValidator validator;
    private PerFileCodeGenerationStrategy perFileStrategy;
    private CodeGenerationCoordinator coordinator;
    private ExecutorService agentTaskExecutor;

    @BeforeEach
    void setUp() {
        objectMapper = new JacksonConfig().objectMapper();
        validator = new ImplementationEngineerValidator(objectMapper, new FeatureManifestValidator());
        agentTaskExecutor = Executors.newFixedThreadPool(2);
        perFileStrategy = new PerFileCodeGenerationStrategy(llmClient, llmModelPolicy, objectMapper);
        coordinator = new CodeGenerationCoordinator(
                contextReducer, llmClient, llmModelPolicy, validatorRegistry, objectMapper,
                agentTaskExecutor, perFileStrategy);
        when(validatorRegistry.getValidator(MidasState.CODE_GENERATION))
                .thenReturn(Optional.of(validator));
        when(llmModelPolicy.resolve(MidasState.CODE_GENERATION)).thenReturn("gemini-2.5-flash");
    }

    @AfterEach
    void tearDown() {
        agentTaskExecutor.shutdownNow();
    }

    @Test
    @DisplayName("SERVER_SIDE model executes per-file pass with server prompt")
    void execute_serverSide_surfaceRoutedSinglePass() throws Exception {
        var spec = objectMapper.readTree("""
                {"runtime_environment":{"execution_model":"SERVER_SIDE"}}
                """);
        var arch = objectMapper.readTree("""
                {"file_layout":["%s"]}
                """.formatted(SERVER_FILE));
        var ctx = MidasContext.start("Build API", "run-001").withTechnicalSpec(spec);
        when(contextReducer.reduceImplementationPass(eq(ctx), eq(ImplementationSurface.SERVER)))
                .thenReturn(view("run-001", "IMPLEMENTATION_ENGINEER_SERVER", arch));

        when(llmClient.call(any())).thenReturn(singleFile(SERVER_FILE, "public class App {}"));

        AgentResult result = coordinator.execute(ctx, "ImplementationEngineerAgent");

        ArgumentCaptor<LlmCallRequest> captor = ArgumentCaptor.forClass(LlmCallRequest.class);
        verify(llmClient, times(1)).call(captor.capture());
        assertThat(captor.getValue().getSystemPrompt())
                .isEqualTo(AgentSystemPrompts.HYBRID_SERVER_IMPLEMENTATION_PROMPT);
        verify(contextReducer).reduceImplementationPass(eq(ctx), eq(ImplementationSurface.SERVER));
        assertThat(result.validatedOutput().get("source_files").has(SERVER_FILE)).isTrue();
        assertThat(result.attemptsUsed()).isEqualTo(1);
    }

    @Test
    @DisplayName("CLIENT_SIDE model executes per-file pass with client prompt")
    void execute_clientSide_surfaceRoutedSinglePass() throws Exception {
        var spec = objectMapper.readTree("""
                {"runtime_environment":{"execution_model":"CLIENT_SIDE"},"core_features":["Popup UI"]}
                """);
        var arch = objectMapper.readTree("""
                {"file_layout":["manifest.json","src/popup.ts"]}
                """);
        var ctx = MidasContext.start("Build extension", "run-client").withTechnicalSpec(spec);
        when(contextReducer.reduceImplementationPass(eq(ctx), eq(ImplementationSurface.CLIENT)))
                .thenReturn(view("run-client", "IMPLEMENTATION_ENGINEER_CLIENT", arch));

        when(llmClient.call(any())).thenReturn(
                singleFile("manifest.json", "{}"),
                singleFile("src/popup.ts", "export const ok = true;"));

        AgentResult result = coordinator.execute(ctx, "ImplementationEngineerAgent");

        verify(llmClient, times(2)).call(any());
        assertThat(result.validatedOutput().get("source_files").has("manifest.json")).isTrue();
        assertThat(result.validatedOutput().get("source_files").has("src/popup.ts")).isTrue();
        assertThat(result.attemptsUsed()).isEqualTo(2);
    }

    @Test
    @DisplayName("CLI model executes per-file generic pass")
    void execute_cli_genericSinglePass() throws Exception {
        var spec = objectMapper.readTree("""
                {"runtime_environment":{"execution_model":"CLI"},"core_features":["CLI main"]}
                """);
        var arch = objectMapper.readTree("""
                {"file_layout":["cmd/main.go"]}
                """);
        var ctx = MidasContext.start("Build CLI tool", "run-cli").withTechnicalSpec(spec);
        stubImplementationView("run-cli", ContextReducer.AgentRole.IMPLEMENTATION_ENGINEER, arch);

        when(llmClient.call(any())).thenReturn(singleFile(CLI_FILE, "package main"));

        AgentResult result = coordinator.execute(ctx, "ImplementationEngineerAgent");

        ArgumentCaptor<LlmCallRequest> captor = ArgumentCaptor.forClass(LlmCallRequest.class);
        verify(llmClient, times(1)).call(captor.capture());
        assertThat(captor.getValue().getSystemPrompt())
                .isEqualTo(AgentSystemPrompts.IMPLEMENTATION_ENGINEER_PROMPT);
        assertThat(result.validatedOutput().get("source_files").has(CLI_FILE)).isTrue();
        assertThat(result.attemptsUsed()).isEqualTo(1);
    }

    @Test
    @DisplayName("HYBRID model executes per-file client and server passes then merges")
    void execute_hybrid_fanOut_mergesBothPasses() throws Exception {
        var spec = objectMapper.readTree("""
                {"runtime_environment":{"execution_model":"HYBRID"}}
                """);
        var clientArch = objectMapper.readTree("""
                {"file_layout":["manifest.json","src/popup.ts"]}
                """);
        var serverArch = objectMapper.readTree("""
                {"file_layout":["%s"]}
                """.formatted(SERVER_FILE));
        var ctx = MidasContext.start("Build hybrid app", "run-hybrid").withTechnicalSpec(spec);

        when(contextReducer.reduceImplementationPass(eq(ctx), eq(ImplementationSurface.CLIENT)))
                .thenReturn(view("run-hybrid", "IMPLEMENTATION_ENGINEER_CLIENT", clientArch));
        when(contextReducer.reduceImplementationPass(eq(ctx), eq(ImplementationSurface.SERVER)))
                .thenReturn(view("run-hybrid", "IMPLEMENTATION_ENGINEER_SERVER", serverArch));

        when(llmClient.call(argThat(req -> req != null && req.getAgentName().endsWith("Client"))))
                .thenReturn(
                        singleFile("manifest.json", "{}"),
                        singleFile("src/popup.ts", "export const ok = true;"));
        when(llmClient.call(argThat(req -> req != null && req.getAgentName().endsWith("Server"))))
                .thenReturn(singleFile(SERVER_FILE, "public class App {}"));

        AgentResult result = coordinator.execute(ctx, "ImplementationEngineerAgent");

        verify(llmClient, times(3)).call(any(LlmCallRequest.class));
        assertThat(result.validatedOutput().get("source_files").size()).isEqualTo(3);
        assertThat(result.validatedOutput().get("feature_manifest")).hasSize(2);
        assertThat(result.attemptsUsed()).isEqualTo(3);
    }

    @Test
    @DisplayName("HYBRID model uses dedicated client and server system prompts")
    void execute_hybrid_usesDedicatedPromptsForEachPass() throws Exception {
        var spec = objectMapper.readTree("""
                {"runtime_environment":{"execution_model":"HYBRID"}}
                """);
        var clientArch = objectMapper.readTree("""
                {"file_layout":["manifest.json"]}
                """);
        var serverArch = objectMapper.readTree("""
                {"file_layout":["%s"]}
                """.formatted(SERVER_FILE));
        var ctx = MidasContext.start("Build hybrid app", "run-hybrid").withTechnicalSpec(spec);

        when(contextReducer.reduceImplementationPass(eq(ctx), eq(ImplementationSurface.CLIENT)))
                .thenReturn(view("run-hybrid", "IMPLEMENTATION_ENGINEER_CLIENT", clientArch));
        when(contextReducer.reduceImplementationPass(eq(ctx), eq(ImplementationSurface.SERVER)))
                .thenReturn(view("run-hybrid", "IMPLEMENTATION_ENGINEER_SERVER", serverArch));
        when(llmClient.call(argThat(req -> req != null && req.getAgentName().endsWith("Client"))))
                .thenReturn(singleFile("manifest.json", "{}"));
        when(llmClient.call(argThat(req -> req != null && req.getAgentName().endsWith("Server"))))
                .thenReturn(singleFile(SERVER_FILE, "public class App {}"));

        coordinator.execute(ctx, "ImplementationEngineerAgent");

        ArgumentCaptor<LlmCallRequest> captor = ArgumentCaptor.forClass(LlmCallRequest.class);
        verify(llmClient, times(2)).call(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(LlmCallRequest::getSystemPrompt)
                .containsExactlyInAnyOrder(
                        AgentSystemPrompts.HYBRID_CLIENT_IMPLEMENTATION_PROMPT,
                        AgentSystemPrompts.HYBRID_SERVER_IMPLEMENTATION_PROMPT);
    }

    @Test
    @DisplayName("HYBRID parallel pass surfaces non-retryable LLM failure without hanging")
    void execute_hybrid_propagatesPassFailure() throws Exception {
        var spec = objectMapper.readTree("""
                {"runtime_environment":{"execution_model":"HYBRID"}}
                """);
        var clientArch = objectMapper.readTree("""
                {"file_layout":["manifest.json"]}
                """);
        var serverArch = objectMapper.readTree("""
                {"file_layout":["%s"]}
                """.formatted(SERVER_FILE));
        var ctx = MidasContext.start("Build hybrid app", "run-hybrid-fail").withTechnicalSpec(spec);

        when(contextReducer.reduceImplementationPass(eq(ctx), eq(ImplementationSurface.CLIENT)))
                .thenReturn(view("run-hybrid-fail", "IMPLEMENTATION_ENGINEER_CLIENT", clientArch));
        when(contextReducer.reduceImplementationPass(eq(ctx), eq(ImplementationSurface.SERVER)))
                .thenReturn(view("run-hybrid-fail", "IMPLEMENTATION_ENGINEER_SERVER", serverArch));
        when(llmClient.call(argThat(req -> req != null && req.getAgentName().endsWith("Client"))))
                .thenReturn(singleFile("manifest.json", "{}"));
        when(llmClient.call(argThat(req -> req != null && req.getAgentName().endsWith("Server"))))
                .thenThrow(LlmCallException.emptyResponse("ImplementationEngineerAgentServer"));

        assertThatThrownBy(() -> coordinator.execute(ctx, "ImplementationEngineerAgent"))
                .isInstanceOf(LlmCallException.class);
    }

    @Test
    @DisplayName("CLI model appends PRODUCT REVIEW REMEDIATION block when directive is present")
    void execute_cli_withRemediationDirective_appendsRemediationBlock() throws Exception {
        var spec = objectMapper.readTree("""
                {"runtime_environment":{"execution_model":"CLI"},"core_features":["CLI main"]}
                """);
        var directive = objectMapper.readTree("""
                {"source_verdict":"REJECT","required_changes":["Add export command"]}
                """);
        var arch = objectMapper.readTree("""
                {"file_layout":["cmd/main.go"]}
                """);
        var ctx = MidasContext.start("Build CLI tool", "run-remediate")
                .withTechnicalSpec(spec)
                .withRemediationDirective(directive);
        stubImplementationView("run-remediate", ContextReducer.AgentRole.IMPLEMENTATION_ENGINEER, arch);

        when(llmClient.call(any())).thenReturn(singleFile(CLI_FILE, "package main"));

        coordinator.execute(ctx, "ImplementationEngineerAgent");

        ArgumentCaptor<LlmCallRequest> captor = ArgumentCaptor.forClass(LlmCallRequest.class);
        verify(llmClient, times(1)).call(captor.capture());
        assertThat(captor.getValue().getSystemPrompt())
                .startsWith(AgentSystemPrompts.IMPLEMENTATION_ENGINEER_PROMPT)
                .contains("--- PRODUCT REVIEW REMEDIATION ---");
    }

    @Test
    @DisplayName("SURGICAL_PATCH merges delta into baseline and uses patch prompt")
    void execute_surgicalPatch_mergesBaselineAndUsesPatchPrompt() throws Exception {
        var spec = objectMapper.readTree("""
                {
                  "runtime_environment":{"execution_model":"CLI"},
                  "core_features":["CLI main"]
                }
                """);
        var baselineSource = objectMapper.readTree("""
                {
                  "cmd/main.go": "package main\\nfunc main() {}",
                  "cmd/util.go": "package main\\nfunc helper() {}"
                }
                """);
        var manifest = objectMapper.readTree("""
                [{
                  "feature_id":"cli-main",
                  "feature_name":"CLI main",
                  "files":["cmd/main.go"],
                  "entry_points":["main"]
                }]
                """);
        var directive = objectMapper.readTree("""
                {
                  "source_verdict":"REJECT",
                  "remediation_mode":"SURGICAL_PATCH",
                  "affected_paths":["cmd/main.go"],
                  "required_changes":["Add export flag"]
                }
                """);
        var ctx = MidasContext.start("Build CLI tool", "run-patch")
                .withTechnicalSpec(spec)
                .withGeneratedSourceCode(baselineSource)
                .withFeatureManifest(manifest)
                .withRemediationDirective(directive);

        when(contextReducer.reducePatchImplementationPass(eq(ctx), eq(java.util.List.of("cmd/main.go"))))
                .thenReturn(view("run-patch", "IMPLEMENTATION_ENGINEER_PATCH"));

        when(llmClient.call(any())).thenReturn(LlmCallResult.ofText("""
                {
                  "source_files": {
                    "cmd/main.go": "package main\\nfunc main() { export() }\\nfunc export() {}"
                  }
                }
                """));

        AgentResult result = coordinator.execute(ctx, "ImplementationEngineerAgent");

        verify(llmClient, times(1)).call(any());
        assertThat(result.validatedOutput().get("source_files").get("cmd/main.go").asText()).contains("export");
        assertThat(result.validatedOutput().get("source_files").get("cmd/util.go").asText()).contains("helper");
    }

    @Test
    @DisplayName("SURGICAL_PATCH falls back to per-file full regeneration when patch is rejected")
    void execute_surgicalPatch_validationFailure_fallsBackToFullRegen() throws Exception {
        var spec = objectMapper.readTree("""
                {
                  "runtime_environment":{"execution_model":"CLI"},
                  "core_features":["CLI main"]
                }
                """);
        var baselineSource = objectMapper.readTree("""
                {"cmd/main.go":"package main"}
                """);
        var manifest = objectMapper.readTree("""
                [{
                  "feature_id":"cli-main",
                  "feature_name":"CLI main",
                  "files":["cmd/main.go"],
                  "entry_points":["main"]
                }]
                """);
        var directive = objectMapper.readTree("""
                {
                  "source_verdict":"REJECT",
                  "remediation_mode":"SURGICAL_PATCH",
                  "affected_paths":["cmd/main.go"],
                  "required_changes":["Fix main"]
                }
                """);
        var arch = objectMapper.readTree("""
                {"file_layout":["cmd/main.go"]}
                """);
        var ctx = MidasContext.start("Build CLI tool", "run-patch-fallback")
                .withTechnicalSpec(spec)
                .withGeneratedSourceCode(baselineSource)
                .withFeatureManifest(manifest)
                .withRemediationDirective(directive);

        when(contextReducer.reducePatchImplementationPass(eq(ctx), eq(java.util.List.of("cmd/main.go"))))
                .thenReturn(view("run-patch-fallback", "IMPLEMENTATION_ENGINEER_PATCH"));
        stubImplementationView("run-patch-fallback", ContextReducer.AgentRole.IMPLEMENTATION_ENGINEER, arch);

        when(llmClient.call(argThat(req -> req != null && req.getSystemPrompt().contains("SURGICAL PATCH on an existing codebase"))))
                .thenReturn(LlmCallResult.ofText("""
                        {"source_files":{"cmd/main.go":"// TODO fix"}}
                        """));
        when(llmClient.call(argThat(req -> req != null && req.getSystemPrompt().startsWith(AgentSystemPrompts.IMPLEMENTATION_ENGINEER_PROMPT))))
                .thenReturn(singleFile(CLI_FILE, "package main"));

        AgentResult result = coordinator.execute(ctx, "ImplementationEngineerAgent");

        verify(llmClient, atLeast(2)).call(any(LlmCallRequest.class));
        assertThat(result.validatedOutput().get("source_files").has(CLI_FILE)).isTrue();
    }

    @Test
    @DisplayName("JSON parse error allows at most one retry per file")
    void execute_parseError_maxOneRetry() throws Exception {
        var spec = objectMapper.readTree("""
                {"runtime_environment":{"execution_model":"CLI"},"core_features":["CLI main"]}
                """);
        var arch = objectMapper.readTree("""
                {"file_layout":["cmd/main.go"]}
                """);
        var ctx = MidasContext.start("Build CLI tool", "run-parse-retry").withTechnicalSpec(spec);
        stubImplementationView("run-parse-retry", ContextReducer.AgentRole.IMPLEMENTATION_ENGINEER, arch);

        when(llmClient.call(any())).thenReturn(LlmCallResult.ofText("not-json-at-all"));

        assertThatThrownBy(() -> coordinator.execute(ctx, "ImplementationEngineerAgent"))
                .isInstanceOf(com.midas.d3.agent.base.AgentExecutionException.class);

        verify(llmClient, times(2)).call(any());
    }

    @Test
    @DisplayName("MAX_TOKENS finishReason fails fast without validation retries")
    void execute_maxTokens_failsFast() throws Exception {
        var spec = objectMapper.readTree("""
                {"runtime_environment":{"execution_model":"CLI"},"core_features":["CLI main"]}
                """);
        var arch = objectMapper.readTree("""
                {"file_layout":["cmd/main.go"]}
                """);
        var ctx = MidasContext.start("Build CLI tool", "run-max-tokens").withTechnicalSpec(spec);
        stubImplementationView("run-max-tokens", ContextReducer.AgentRole.IMPLEMENTATION_ENGINEER, arch);

        when(llmClient.call(any())).thenReturn(LlmCallResult.of(
                "partial-json", "gemini-2.5-flash", 100, 50, LlmCallResult.FINISH_REASON_MAX_TOKENS));

        assertThatThrownBy(() -> coordinator.execute(ctx, "ImplementationEngineerAgent"))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("MAX_TOKENS");

        verify(llmClient, times(1)).call(any());
    }

    private LlmCallResult singleFile(String path, String content) {
        // Per-file generation now expects raw source in a single markdown code block
        // (the no-JSON contract enforced by ImplementationEngineerValidator#validateSingleFileOutput);
        // a JSON envelope is explicitly rejected. The surgical-patch path is separate and still
        // returns a {"source_files":...} JSON object (validated by validatePatchOutput).
        return LlmCallResult.ofText("```" + languageHint(path) + "\n" + content + "\n```\n");
    }

    private static String languageHint(String path) {
        int dot = path.lastIndexOf('.');
        return dot >= 0 ? path.substring(dot + 1) : "";
    }

    private void stubImplementationView(String runId, ContextReducer.AgentRole role, JsonNode architecture) {
        when(contextReducer.reduce(any(), eq(role)))
                .thenReturn(view(runId, role.name(), architecture));
    }

    private AgentContextView view(String runId, String agentName) {
        return view(runId, agentName, null);
    }

    private AgentContextView view(String runId, String agentName, JsonNode architecture) {
        Map<String, JsonNode> artifacts = new java.util.LinkedHashMap<>();
        if (architecture != null) {
            artifacts.put("architectureDesign", architecture);
        }
        return AgentContextView.builder()
                .agentName(agentName)
                .pipelineRunId(runId)
                .rawUserIdea("idea")
                .requiredArtifacts(artifacts)
                .estimatedTokenBudget(10)
                .build();
    }
}

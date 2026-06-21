package com.midas.d3.agent.implementation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.midas.d3.agent.AgentSystemPrompts;
import com.midas.d3.agent.base.AgentExecutionException;
import com.midas.d3.config.JacksonConfig;
import com.midas.d3.context.AgentContextView;
import com.midas.d3.context.MidasContext;
import com.midas.d3.llm.LlmCallException;
import com.midas.d3.llm.LlmCallRequest;
import com.midas.d3.llm.LlmCallResult;
import com.midas.d3.llm.LlmClient;
import com.midas.d3.llm.LlmModelPolicy;
import com.midas.d3.statemachine.MidasState;
import com.midas.d3.validation.FeatureManifestValidator;
import com.midas.d3.validation.ImplementationEngineerValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PerFileCodeGenerationStrategy")
class PerFileCodeGenerationStrategyTest {

    @Mock private LlmClient llmClient;
    @Mock private LlmModelPolicy llmModelPolicy;

    private ObjectMapper objectMapper;
    private ImplementationEngineerValidator validator;
    private PerFileCodeGenerationStrategy strategy;

    @BeforeEach
    void setUp() {
        objectMapper = new JacksonConfig().objectMapper();
        validator = new ImplementationEngineerValidator(objectMapper, new FeatureManifestValidator());
        strategy = new PerFileCodeGenerationStrategy(llmClient, llmModelPolicy, objectMapper);
    }

    private void stubModelPolicy() {
        when(llmModelPolicy.resolve(MidasState.CODE_GENERATION)).thenReturn("gemini-2.5-flash");
    }

    @Test
    @DisplayName("generates one LLM call per file_layout entry and assembles envelope")
    void generatePass_oneCallPerFile() throws Exception {
        stubModelPolicy();
        JsonNode architecture = objectMapper.readTree("""
                {"file_layout":["manifest.json","src/popup.ts"]}
                """);
        AgentContextView view = viewWithArchitecture(architecture);
        var spec = objectMapper.readTree("""
                {"runtime_environment":{"execution_model":"CLIENT_SIDE"},"core_features":["Popup UI"]}
                """);
        MidasContext ctx = MidasContext.start("extension", "run-per-file").withTechnicalSpec(spec);

        when(llmClient.call(any())).thenReturn(
                LlmCallResult.ofText("""
                        ```json
                        {}
                        ```
                        """),
                LlmCallResult.ofText("""
                        ```typescript
                        export const ok = true;
                        ```
                        """));

        PerFileCodeGenerationStrategy.PassResult result = strategy.generatePass(
                ctx, view, ImplementationSurface.CLIENT,
                AgentSystemPrompts.HYBRID_CLIENT_IMPLEMENTATION_PROMPT,
                "ImplementationEngineerClient", validator, false);

        verify(llmClient, times(2)).call(any(LlmCallRequest.class));
        assertThat(result.sourceFiles().size()).isEqualTo(2);
        assertThat(result.sourceFiles().has("manifest.json")).isTrue();
        assertThat(result.featureManifest()).hasSize(1);
        assertThat(result.attemptsUsed()).isEqualTo(2);
    }

    @Test
    @DisplayName("HYBRID partial pass uses surface-scoped manifest id")
    void generatePass_hybridPartial_usesSurfaceManifestId() throws Exception {
        stubModelPolicy();
        JsonNode architecture = objectMapper.readTree("""
                {"file_layout":["manifest.json"]}
                """);
        AgentContextView view = viewWithArchitecture(architecture);
        MidasContext ctx = MidasContext.start("hybrid", "run-hybrid-partial")
                .withTechnicalSpec(objectMapper.readTree("""
                        {"runtime_environment":{"execution_model":"HYBRID"}}
                        """));

        when(llmClient.call(any())).thenReturn(LlmCallResult.ofText("""
                ```json
                {}
                ```
                """));

        PerFileCodeGenerationStrategy.PassResult result = strategy.generatePass(
                ctx, view, ImplementationSurface.CLIENT,
                AgentSystemPrompts.HYBRID_CLIENT_IMPLEMENTATION_PROMPT,
                "ImplementationEngineerClient", validator, true);

        assertThat(result.featureManifest().get(0).get("feature_id").asText()).isEqualTo("client-surface");
    }

    @Test
    @DisplayName("empty file_layout fails fast")
    void generatePass_emptyLayout_throws() {
        AgentContextView view = viewWithArchitecture(objectMapper.createObjectNode());
        MidasContext ctx = MidasContext.start("empty", "run-empty")
                .withTechnicalSpec(objectMapper.createObjectNode());

        assertThatThrownBy(() -> strategy.generatePass(
                ctx, view, null, AgentSystemPrompts.IMPLEMENTATION_ENGINEER_PROMPT,
                "ImplementationEngineer", validator, false))
                .isInstanceOf(AgentExecutionException.class)
                .hasMessageContaining("file_layout");
    }

    @Test
    @DisplayName("MAX_TOKENS fails fast on first file")
    void generatePass_maxTokens_failsFast() throws Exception {
        stubModelPolicy();
        JsonNode architecture = objectMapper.readTree("""
                {"file_layout":["only.go"]}
                """);
        AgentContextView view = viewWithArchitecture(architecture);
        MidasContext ctx = MidasContext.start("cli", "run-max")
                .withTechnicalSpec(objectMapper.readTree("""
                        {"core_features":["CLI"]}
                        """));

        when(llmClient.call(any())).thenReturn(LlmCallResult.of(
                "partial", "gemini-2.5-flash", 10, 5, LlmCallResult.FINISH_REASON_MAX_TOKENS));

        assertThatThrownBy(() -> strategy.generatePass(
                ctx, view, null, AgentSystemPrompts.IMPLEMENTATION_ENGINEER_PROMPT,
                "ImplementationEngineer", validator, false))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("MAX_TOKENS");

        verify(llmClient, times(1)).call(any());
    }

    @Test
    @DisplayName("resolveFileLayout reads paths from architectureDesign artifact")
    void resolveFileLayout_readsArchitecture() throws Exception {
        JsonNode architecture = objectMapper.readTree("""
                {"file_layout":["a.ts","b.ts"]}
                """);
        assertThat(PerFileCodeGenerationStrategy.resolveFileLayout(viewWithArchitecture(architecture)))
                .containsExactly("a.ts", "b.ts");
    }

    private AgentContextView viewWithArchitecture(JsonNode architecture) {
        ObjectNode arch = architecture.isObject()
                ? (ObjectNode) architecture
                : objectMapper.createObjectNode();
        if (!arch.has("file_layout")) {
            arch.set("file_layout", objectMapper.createArrayNode());
        }
        return AgentContextView.builder()
                .agentName("IMPLEMENTATION_ENGINEER")
                .pipelineRunId("run")
                .rawUserIdea("idea")
                .requiredArtifacts(Map.of("architectureDesign", arch))
                .estimatedTokenBudget(10)
                .build();
    }
}

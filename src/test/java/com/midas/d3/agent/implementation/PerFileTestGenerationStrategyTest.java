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
import com.midas.d3.validation.QaEngineerValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PerFileTestGenerationStrategy")
class PerFileTestGenerationStrategyTest {

    @Mock private LlmClient llmClient;
    @Mock private LlmModelPolicy llmModelPolicy;

    private ObjectMapper objectMapper;
    private QaEngineerValidator validator;
    private PerFileTestGenerationStrategy strategy;

    @BeforeEach
    void setUp() {
        objectMapper = new JacksonConfig().objectMapper();
        validator = new QaEngineerValidator(objectMapper);
        strategy = new PerFileTestGenerationStrategy(llmClient, llmModelPolicy, objectMapper);
    }

    private void stubModelPolicy() {
        when(llmModelPolicy.resolve(MidasState.TEST_GENERATION)).thenReturn("gemini-2.5-flash");
    }

    @Test
    @DisplayName("generates one LLM call per test path in file_layout")
    void generatePass_oneCallPerTestFile() throws Exception {
        stubModelPolicy();
        JsonNode architecture = objectMapper.readTree("""
                {"file_layout":["src/popup.ts","src/popup.test.ts","src/util.test.ts"]}
                """);
        AgentContextView view = viewWithArchitecture(architecture);
        MidasContext ctx = MidasContext.start("extension", "run-per-file-test");

        when(llmClient.call(any())).thenReturn(
                LlmCallResult.ofText("""
                        ```typescript
                        describe('popup', () => { it('works', () => expect(true).toBe(true)); });
                        ```
                        """),
                LlmCallResult.ofText("""
                        ```typescript
                        describe('util', () => { it('works', () => expect(true).toBe(true)); });
                        ```
                        """));

        PerFileTestGenerationStrategy.PassResult result = strategy.generatePass(
                ctx, view, ImplementationSurface.CLIENT,
                AgentSystemPrompts.HYBRID_CLIENT_QA_PROMPT,
                "QaAutomationAgentClient", validator);

        verify(llmClient, times(2)).call(any(LlmCallRequest.class));
        assertThat(result.validated().size()).isEqualTo(2);
        assertThat(result.validated().has("src/popup.test.ts")).isTrue();
        assertThat(result.validated().has("src/util.test.ts")).isTrue();
        assertThat(result.attemptsUsed()).isEqualTo(2);
    }

    @Test
    @DisplayName("resolveTestFileLayout filters only test paths from file_layout")
    void resolveTestFileLayout_filtersTestPaths() throws Exception {
        JsonNode architecture = objectMapper.readTree("""
                {
                  "file_layout":[
                    "manifest.json",
                    "src/main/java/com/example/App.java",
                    "src/test/java/com/example/AppTest.java",
                    "src/popup.test.ts"
                  ]
                }
                """);
        assertThat(PerFileTestGenerationStrategy.resolveTestFileLayout(viewWithArchitecture(architecture)))
                .containsExactly(
                        "src/test/java/com/example/AppTest.java",
                        "src/popup.test.ts");
    }

    @Test
    @DisplayName("deriveTestPathsFromLayout maps source paths to *.test.* when architect omits tests")
    void deriveTestPathsFromLayout_mapsSourcePaths() throws Exception {
        JsonNode architecture = objectMapper.readTree("""
                {"file_layout":["manifest.json","content_script.js","service_worker.js","sidebar.js"]}
                """);
        assertThat(PerFileTestGenerationStrategy.resolveTestFileLayout(viewWithArchitecture(architecture)))
                .containsExactly(
                        "content_script.test.js",
                        "service_worker.test.js",
                        "sidebar.test.js");
    }

    @Test
    @DisplayName("empty derivable test file_layout fails fast")
    void generatePass_noTestPaths_throws() throws Exception {
        JsonNode architecture = objectMapper.readTree("""
                {"file_layout":["manifest.json","sidebar.html"]}
                """);
        AgentContextView view = viewWithArchitecture(architecture);
        MidasContext ctx = MidasContext.start("extension", "run-empty-tests");

        assertThatThrownBy(() -> strategy.generatePass(
                ctx, view, ImplementationSurface.CLIENT,
                AgentSystemPrompts.HYBRID_CLIENT_QA_PROMPT,
                "QaAutomationAgent", validator))
                .isInstanceOf(AgentExecutionException.class)
                .hasMessageContaining("no test file paths");
    }

    @Test
    @DisplayName("prompt-cache contract: retry carries correction in volatile suffix, prefix unchanged")
    void retry_cacheablePrefix_isPreservedAcrossAttempts() throws Exception {
        stubModelPolicy();
        JsonNode architecture = objectMapper.readTree("""
                {"file_layout":["src/popup.test.ts"]}
                """);
        AgentContextView view = viewWithArchitecture(architecture);
        MidasContext ctx = MidasContext.start("cache-test", "run-cache");

        when(llmClient.call(any()))
                .thenReturn(LlmCallResult.ofText("not a code block"))
                .thenReturn(LlmCallResult.ofText("""
                        ```typescript
                        describe('popup', () => { it('works', () => expect(true).toBe(true)); });
                        ```
                        """));

        strategy.generatePass(ctx, view, null,
                AgentSystemPrompts.QA_ENGINEER_PROMPT,
                "QaAutomationAgent", validator);

        ArgumentCaptor<LlmCallRequest> captor = ArgumentCaptor.forClass(LlmCallRequest.class);
        verify(llmClient, times(2)).call(captor.capture());
        List<LlmCallRequest> requests = captor.getAllValues();
        LlmCallRequest attempt1 = requests.get(0);
        LlmCallRequest attempt2 = requests.get(1);

        assertThat(attempt2.getCacheableUserPrefix())
                .as("cacheable prefix must be byte-identical across retry attempts")
                .isEqualTo(attempt1.getCacheableUserPrefix());
        assertThat(attempt1.hasVolatileSuffix()).isFalse();
        assertThat(attempt2.hasVolatileSuffix()).isTrue();
        assertThat(attempt2.getVolatileSuffix()).contains("CORRECTION REQUIRED");
        assertThat(attempt2.getCacheableUserPrefix()).doesNotContain("CORRECTION REQUIRED");
    }

    @Test
    @DisplayName("MAX_TOKENS fails fast on first test file")
    void generatePass_maxTokens_failsFast() throws Exception {
        stubModelPolicy();
        JsonNode architecture = objectMapper.readTree("""
                {"file_layout":["src/popup.test.ts"]}
                """);
        AgentContextView view = viewWithArchitecture(architecture);
        MidasContext ctx = MidasContext.start("extension", "run-max");

        when(llmClient.call(any())).thenReturn(LlmCallResult.of(
                "partial", "gemini-2.5-flash", 10, 5, LlmCallResult.FINISH_REASON_MAX_TOKENS));

        assertThatThrownBy(() -> strategy.generatePass(
                ctx, view, null, AgentSystemPrompts.QA_ENGINEER_PROMPT,
                "QaAutomationAgent", validator))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("MAX_TOKENS");

        verify(llmClient, times(1)).call(any());
    }

    private AgentContextView viewWithArchitecture(JsonNode architecture) {
        ObjectNode arch = architecture.isObject()
                ? (ObjectNode) architecture
                : objectMapper.createObjectNode();
        if (!arch.has("file_layout")) {
            arch.set("file_layout", objectMapper.createArrayNode());
        }
        return AgentContextView.builder()
                .agentName("QA_ENGINEER")
                .pipelineRunId("run")
                .rawUserIdea("idea")
                .requiredArtifacts(Map.of(
                        "architectureDesign", arch,
                        "generatedSourceCode", objectMapper.createObjectNode().put("src/popup.ts", "export const x = 1;")))
                .estimatedTokenBudget(10)
                .build();
    }
}

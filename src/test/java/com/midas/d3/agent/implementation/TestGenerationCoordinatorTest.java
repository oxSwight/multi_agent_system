package com.midas.d3.agent.implementation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.midas.d3.agent.AgentSystemPrompts;
import com.midas.d3.agent.base.AgentResult;
import com.midas.d3.config.JacksonConfig;
import com.midas.d3.context.AgentContextView;
import com.midas.d3.context.ContextReducer;
import com.midas.d3.context.MidasContext;
import com.midas.d3.llm.LlmCallException;
import com.midas.d3.llm.LlmCallRequest;
import com.midas.d3.llm.LlmClient;
import com.midas.d3.statemachine.MidasState;
import com.midas.d3.statemachine.ValidatorRegistry;
import com.midas.d3.validation.GoalKeeperValidator;
import com.midas.d3.validation.QaEngineerValidator;
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

@ExtendWith(MockitoExtension.class)
@DisplayName("TestGenerationCoordinator")
class TestGenerationCoordinatorTest {

    @Mock private ContextReducer contextReducer;
    @Mock private LlmClient llmClient;
    @Mock private ValidatorRegistry validatorRegistry;

    private ObjectMapper objectMapper;
    private GoalKeeperValidator validator;
    private TestGenerationCoordinator coordinator;
    private ExecutorService agentTaskExecutor;

    @BeforeEach
    void setUp() {
        objectMapper = new JacksonConfig().objectMapper();
        validator = new QaEngineerValidator(objectMapper);
        agentTaskExecutor = Executors.newFixedThreadPool(2);
        coordinator = new TestGenerationCoordinator(
                contextReducer, llmClient, validatorRegistry, objectMapper, agentTaskExecutor);
        when(validatorRegistry.getValidator(MidasState.TEST_GENERATION))
                .thenReturn(Optional.of(validator));
    }

    @AfterEach
    void tearDown() {
        agentTaskExecutor.shutdownNow();
    }

    @Test
    @DisplayName("non-HYBRID model executes a single LLM pass")
    void execute_nonHybrid_singlePass() throws Exception {
        var spec = objectMapper.readTree("""
                {"runtime_environment":{"execution_model":"SERVER_SIDE"}}
                """);
        var ctx = MidasContext.start("Build API", "run-001").withTechnicalSpec(spec);
        stubQaView("run-001", ContextReducer.AgentRole.QA_ENGINEER);

        when(llmClient.call(any())).thenReturn("""
                {"src/test/java/com/example/AppTest.java":"class AppTest { @Test void ok() {} }"}
                """);

        AgentResult result = coordinator.execute(ctx, "QaAutomationAgent");

        verify(llmClient, times(1)).call(any());
        assertThat(result.validatedOutput().has("src/test/java/com/example/AppTest.java")).isTrue();
        assertThat(result.attemptsUsed()).isEqualTo(1);
    }

    @Test
    @DisplayName("HYBRID model executes client and server passes then merges outputs")
    void execute_hybrid_fanOut_mergesBothPasses() throws Exception {
        var spec = objectMapper.readTree("""
                {"runtime_environment":{"execution_model":"HYBRID"}}
                """);
        var arch = objectMapper.readTree("""
                {"architecture_style":"CLIENT_SERVER","file_layout":["manifest.json","App.java"]}
                """);
        var ctx = MidasContext.start("Build hybrid app", "run-hybrid")
                .withTechnicalSpec(spec)
                .withArchitectureDesign(arch);

        when(contextReducer.reduceTestGenerationPass(eq(ctx), eq(ImplementationSurface.CLIENT)))
                .thenReturn(view("run-hybrid", "QA_ENGINEER_CLIENT"));
        when(contextReducer.reduceTestGenerationPass(eq(ctx), eq(ImplementationSurface.SERVER)))
                .thenReturn(view("run-hybrid", "QA_ENGINEER_SERVER"));

        when(llmClient.call(argThat(req -> req != null && req.getAgentName().endsWith("Client"))))
                .thenReturn("{\"src/popup.test.ts\":\"describe('popup', () => { it('works', () => expect(true).toBe(true)); });\"}");
        when(llmClient.call(argThat(req -> req != null && req.getAgentName().endsWith("Server"))))
                .thenReturn("{\"src/test/java/com/example/AppTest.java\":\"class AppTest { @Test void ok() {} }\"}");

        AgentResult result = coordinator.execute(ctx, "QaAutomationAgent");

        verify(llmClient, times(2)).call(any(LlmCallRequest.class));
        assertThat(result.validatedOutput().size()).isEqualTo(2);
        assertThat(result.validatedOutput().has("src/popup.test.ts")).isTrue();
        assertThat(result.validatedOutput().has("src/test/java/com/example/AppTest.java")).isTrue();
        assertThat(result.attemptsUsed()).isEqualTo(2);
    }

    @Test
    @DisplayName("HYBRID model uses dedicated client and server system prompts")
    void execute_hybrid_usesDedicatedPromptsForEachPass() throws Exception {
        var spec = objectMapper.readTree("""
                {"runtime_environment":{"execution_model":"HYBRID"}}
                """);
        var ctx = MidasContext.start("Build hybrid app", "run-hybrid").withTechnicalSpec(spec);

        when(contextReducer.reduceTestGenerationPass(eq(ctx), eq(ImplementationSurface.CLIENT)))
                .thenReturn(view("run-hybrid", "QA_ENGINEER_CLIENT"));
        when(contextReducer.reduceTestGenerationPass(eq(ctx), eq(ImplementationSurface.SERVER)))
                .thenReturn(view("run-hybrid", "QA_ENGINEER_SERVER"));
        when(llmClient.call(argThat(req -> req != null && req.getAgentName().endsWith("Client"))))
                .thenReturn("{\"src/popup.test.ts\":\"describe('x', () => { it('y', () => expect(1).toBe(1)); });\"}");
        when(llmClient.call(argThat(req -> req != null && req.getAgentName().endsWith("Server"))))
                .thenReturn("{\"src/test/java/com/example/AppTest.java\":\"class AppTest { @Test void ok() {} }\"}");

        coordinator.execute(ctx, "QaAutomationAgent");

        ArgumentCaptor<LlmCallRequest> captor = ArgumentCaptor.forClass(LlmCallRequest.class);
        verify(llmClient, times(2)).call(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(LlmCallRequest::getSystemPrompt)
                .containsExactlyInAnyOrder(
                        AgentSystemPrompts.HYBRID_CLIENT_QA_PROMPT,
                        AgentSystemPrompts.HYBRID_SERVER_QA_PROMPT);
    }

    @Test
    @DisplayName("HYBRID parallel pass surfaces non-retryable LLM failure without hanging")
    void execute_hybrid_propagatesPassFailure() throws Exception {
        var spec = objectMapper.readTree("""
                {"runtime_environment":{"execution_model":"HYBRID"}}
                """);
        var ctx = MidasContext.start("Build hybrid app", "run-hybrid-fail").withTechnicalSpec(spec);

        when(contextReducer.reduceTestGenerationPass(eq(ctx), eq(ImplementationSurface.CLIENT)))
                .thenReturn(view("run-hybrid-fail", "QA_ENGINEER_CLIENT"));
        when(contextReducer.reduceTestGenerationPass(eq(ctx), eq(ImplementationSurface.SERVER)))
                .thenReturn(view("run-hybrid-fail", "QA_ENGINEER_SERVER"));
        when(llmClient.call(argThat(req -> req != null && req.getAgentName().endsWith("Client"))))
                .thenReturn("{\"src/popup.test.ts\":\"describe('x', () => { it('y', () => expect(1).toBe(1)); });\"}");
        when(llmClient.call(argThat(req -> req != null && req.getAgentName().endsWith("Server"))))
                .thenThrow(LlmCallException.emptyResponse("QaAutomationAgentServer"));

        assertThatThrownBy(() -> coordinator.execute(ctx, "QaAutomationAgent"))
                .isInstanceOf(LlmCallException.class);
    }

    private void stubQaView(String runId, ContextReducer.AgentRole role) {
        when(contextReducer.reduce(any(), eq(role)))
                .thenReturn(view(runId, role.name()));
    }

    private AgentContextView view(String runId, String agentName) {
        return AgentContextView.builder()
                .agentName(agentName)
                .pipelineRunId(runId)
                .rawUserIdea("idea")
                .requiredArtifacts(Map.of())
                .estimatedTokenBudget(10)
                .build();
    }
}

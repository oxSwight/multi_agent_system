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
import com.midas.d3.llm.LlmCallResult;
import com.midas.d3.llm.LlmClient;
import com.midas.d3.llm.LlmModelPolicy;
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
    @Mock private LlmModelPolicy llmModelPolicy;
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
                contextReducer, llmClient, llmModelPolicy, validatorRegistry, objectMapper, agentTaskExecutor);
        when(validatorRegistry.getValidator(MidasState.TEST_GENERATION))
                .thenReturn(Optional.of(validator));
        when(llmModelPolicy.resolve(MidasState.TEST_GENERATION)).thenReturn("gemini-1.5-pro");
    }

    @AfterEach
    void tearDown() {
        agentTaskExecutor.shutdownNow();
    }

    @Test
    @DisplayName("SERVER_SIDE model executes a single surface-routed pass with server prompt")
    void execute_serverSide_surfaceRoutedSinglePass() throws Exception {
        var spec = objectMapper.readTree("""
                {"runtime_environment":{"execution_model":"SERVER_SIDE"}}
                """);
        var ctx = MidasContext.start("Build API", "run-001").withTechnicalSpec(spec);
        when(contextReducer.reduceTestGenerationPass(eq(ctx), eq(ImplementationSurface.SERVER)))
                .thenReturn(view("run-001", "QA_ENGINEER_SERVER"));

        when(llmClient.call(any())).thenReturn(LlmCallResult.ofText("""
                {"src/test/java/com/example/AppTest.java":"class AppTest { @Test void ok() {} }"}
                """));

        AgentResult result = coordinator.execute(ctx, "QaAutomationAgent");

        ArgumentCaptor<LlmCallRequest> captor = ArgumentCaptor.forClass(LlmCallRequest.class);
        verify(llmClient, times(1)).call(captor.capture());
        assertThat(captor.getValue().getSystemPrompt())
                .isEqualTo(AgentSystemPrompts.HYBRID_SERVER_QA_PROMPT);
        assertThat(captor.getValue().getModelOverride()).isEqualTo("gemini-1.5-pro");
        verify(contextReducer).reduceTestGenerationPass(eq(ctx), eq(ImplementationSurface.SERVER));
        assertThat(result.validatedOutput().has("src/test/java/com/example/AppTest.java")).isTrue();
        assertThat(result.attemptsUsed()).isEqualTo(1);
    }

    @Test
    @DisplayName("CLIENT_SIDE model executes a single surface-routed pass with client prompt")
    void execute_clientSide_surfaceRoutedSinglePass() throws Exception {
        var spec = objectMapper.readTree("""
                {"runtime_environment":{"execution_model":"CLIENT_SIDE"}}
                """);
        var ctx = MidasContext.start("Build extension", "run-client").withTechnicalSpec(spec);
        when(contextReducer.reduceTestGenerationPass(eq(ctx), eq(ImplementationSurface.CLIENT)))
                .thenReturn(view("run-client", "QA_ENGINEER_CLIENT"));

        when(llmClient.call(any())).thenReturn(LlmCallResult.ofText("""
                {"src/popup.test.ts":"describe('popup', () => { it('works', () => expect(true).toBe(true)); });"}
                """));

        AgentResult result = coordinator.execute(ctx, "QaAutomationAgent");

        ArgumentCaptor<LlmCallRequest> captor = ArgumentCaptor.forClass(LlmCallRequest.class);
        verify(llmClient, times(1)).call(captor.capture());
        assertThat(captor.getValue().getSystemPrompt())
                .isEqualTo(AgentSystemPrompts.HYBRID_CLIENT_QA_PROMPT);
        verify(contextReducer).reduceTestGenerationPass(eq(ctx), eq(ImplementationSurface.CLIENT));
        assertThat(result.validatedOutput().has("src/popup.test.ts")).isTrue();
        assertThat(result.attemptsUsed()).isEqualTo(1);
    }

    @Test
    @DisplayName("CLI model executes a single generic LLM pass")
    void execute_cli_genericSinglePass() throws Exception {
        var spec = objectMapper.readTree("""
                {"runtime_environment":{"execution_model":"CLI"}}
                """);
        var ctx = MidasContext.start("Build CLI tool", "run-cli").withTechnicalSpec(spec);
        stubQaView("run-cli", ContextReducer.AgentRole.QA_ENGINEER);

        when(llmClient.call(any())).thenReturn(LlmCallResult.ofText("""
                {"cmd/main_test.go":"func TestMain(t *testing.T) {}"}
                """));

        AgentResult result = coordinator.execute(ctx, "QaAutomationAgent");

        ArgumentCaptor<LlmCallRequest> captor = ArgumentCaptor.forClass(LlmCallRequest.class);
        verify(llmClient, times(1)).call(captor.capture());
        assertThat(captor.getValue().getSystemPrompt())
                .isEqualTo(AgentSystemPrompts.QA_ENGINEER_PROMPT);
        verify(contextReducer).reduce(any(), eq(ContextReducer.AgentRole.QA_ENGINEER));
        assertThat(result.validatedOutput().has("cmd/main_test.go")).isTrue();
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
                .thenReturn(LlmCallResult.ofText("{\"src/popup.test.ts\":\"describe('popup', () => { it('works', () => expect(true).toBe(true)); });\"}"));
        when(llmClient.call(argThat(req -> req != null && req.getAgentName().endsWith("Server"))))
                .thenReturn(LlmCallResult.ofText("{\"src/test/java/com/example/AppTest.java\":\"class AppTest { @Test void ok() {} }\"}"));

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
                .thenReturn(LlmCallResult.ofText("{\"src/popup.test.ts\":\"describe('x', () => { it('y', () => expect(1).toBe(1)); });\"}"));
        when(llmClient.call(argThat(req -> req != null && req.getAgentName().endsWith("Server"))))
                .thenReturn(LlmCallResult.ofText("{\"src/test/java/com/example/AppTest.java\":\"class AppTest { @Test void ok() {} }\"}"));

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
                .thenReturn(LlmCallResult.ofText("{\"src/popup.test.ts\":\"describe('x', () => { it('y', () => expect(1).toBe(1)); });\"}"));
        when(llmClient.call(argThat(req -> req != null && req.getAgentName().endsWith("Server"))))
                .thenThrow(LlmCallException.emptyResponse("QaAutomationAgentServer"));

        assertThatThrownBy(() -> coordinator.execute(ctx, "QaAutomationAgent"))
                .isInstanceOf(LlmCallException.class);
    }

    @Test
    @DisplayName("CLI model appends PRODUCT REVIEW REMEDIATION block when directive is present")
    void execute_cli_withRemediationDirective_appendsRemediationBlock() throws Exception {
        var spec = objectMapper.readTree("""
                {"runtime_environment":{"execution_model":"CLI"}}
                """);
        var directive = objectMapper.readTree("""
                {"source_verdict":"REJECT","required_changes":["Cover export command"]}
                """);
        var ctx = MidasContext.start("Build CLI tool", "run-remediate")
                .withTechnicalSpec(spec)
                .withRemediationDirective(directive);
        stubQaView("run-remediate", ContextReducer.AgentRole.QA_ENGINEER);

        when(llmClient.call(any())).thenReturn(LlmCallResult.ofText("""
                {"cmd/main_test.go":"func TestMain(t *testing.T) {}"}
                """));

        coordinator.execute(ctx, "QaAutomationAgent");

        ArgumentCaptor<LlmCallRequest> captor = ArgumentCaptor.forClass(LlmCallRequest.class);
        verify(llmClient, times(1)).call(captor.capture());
        assertThat(captor.getValue().getSystemPrompt())
                .startsWith(AgentSystemPrompts.QA_ENGINEER_PROMPT)
                .contains("--- PRODUCT REVIEW REMEDIATION ---")
                .contains("Cover export command")
                .contains("Do NOT rewrite or redesign the entire upstream architecture");
    }

    @Test
    @DisplayName("CLI model without remediation directive uses base prompt unchanged")
    void execute_cli_withoutRemediationDirective_usesBasePrompt() throws Exception {
        var spec = objectMapper.readTree("""
                {"runtime_environment":{"execution_model":"CLI"}}
                """);
        var ctx = MidasContext.start("Build CLI tool", "run-cli").withTechnicalSpec(spec);
        stubQaView("run-cli", ContextReducer.AgentRole.QA_ENGINEER);

        when(llmClient.call(any())).thenReturn(LlmCallResult.ofText("""
                {"cmd/main_test.go":"func TestMain(t *testing.T) {}"}
                """));

        coordinator.execute(ctx, "QaAutomationAgent");

        ArgumentCaptor<LlmCallRequest> captor = ArgumentCaptor.forClass(LlmCallRequest.class);
        verify(llmClient, times(1)).call(captor.capture());
        assertThat(captor.getValue().getSystemPrompt())
                .isEqualTo(AgentSystemPrompts.QA_ENGINEER_PROMPT);
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

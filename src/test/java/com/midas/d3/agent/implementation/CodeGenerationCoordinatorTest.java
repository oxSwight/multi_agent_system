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

@ExtendWith(MockitoExtension.class)
@DisplayName("CodeGenerationCoordinator")
class CodeGenerationCoordinatorTest {

    @Mock private ContextReducer contextReducer;
    @Mock private LlmClient llmClient;
    @Mock private LlmModelPolicy llmModelPolicy;
    @Mock private ValidatorRegistry validatorRegistry;

    private ObjectMapper objectMapper;
    private ImplementationEngineerValidator validator;
    private CodeGenerationCoordinator coordinator;
    private ExecutorService agentTaskExecutor;

    @BeforeEach
    void setUp() {
        objectMapper = new JacksonConfig().objectMapper();
        validator = new ImplementationEngineerValidator(objectMapper);
        agentTaskExecutor = Executors.newFixedThreadPool(2);
        coordinator = new CodeGenerationCoordinator(
                contextReducer, llmClient, llmModelPolicy, validatorRegistry, objectMapper, agentTaskExecutor);
        when(validatorRegistry.getValidator(MidasState.CODE_GENERATION))
                .thenReturn(Optional.of(validator));
        when(llmModelPolicy.resolve(MidasState.CODE_GENERATION)).thenReturn("gemini-1.5-pro");
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
        when(contextReducer.reduceImplementationPass(eq(ctx), eq(ImplementationSurface.SERVER)))
                .thenReturn(view("run-001", "IMPLEMENTATION_ENGINEER_SERVER"));

        when(llmClient.call(any())).thenReturn(LlmCallResult.ofText("""
                {"src/main/java/com/example/App.java":"public class App {}"}
                """));

        AgentResult result = coordinator.execute(ctx, "ImplementationEngineerAgent");

        ArgumentCaptor<LlmCallRequest> captor = ArgumentCaptor.forClass(LlmCallRequest.class);
        verify(llmClient, times(1)).call(captor.capture());
        assertThat(captor.getValue().getSystemPrompt())
                .isEqualTo(AgentSystemPrompts.HYBRID_SERVER_IMPLEMENTATION_PROMPT);
        assertThat(captor.getValue().getModelOverride()).isEqualTo("gemini-1.5-pro");
        verify(contextReducer).reduceImplementationPass(eq(ctx), eq(ImplementationSurface.SERVER));
        assertThat(result.validatedOutput().has("src/main/java/com/example/App.java")).isTrue();
        assertThat(result.attemptsUsed()).isEqualTo(1);
    }

    @Test
    @DisplayName("CLIENT_SIDE model executes a single surface-routed pass with client prompt")
    void execute_clientSide_surfaceRoutedSinglePass() throws Exception {
        var spec = objectMapper.readTree("""
                {"runtime_environment":{"execution_model":"CLIENT_SIDE"}}
                """);
        var ctx = MidasContext.start("Build extension", "run-client").withTechnicalSpec(spec);
        when(contextReducer.reduceImplementationPass(eq(ctx), eq(ImplementationSurface.CLIENT)))
                .thenReturn(view("run-client", "IMPLEMENTATION_ENGINEER_CLIENT"));

        when(llmClient.call(any())).thenReturn(LlmCallResult.ofText("""
                {"manifest.json":"{}", "src/popup.ts":"export const ok = true;"}
                """));

        AgentResult result = coordinator.execute(ctx, "ImplementationEngineerAgent");

        ArgumentCaptor<LlmCallRequest> captor = ArgumentCaptor.forClass(LlmCallRequest.class);
        verify(llmClient, times(1)).call(captor.capture());
        assertThat(captor.getValue().getSystemPrompt())
                .isEqualTo(AgentSystemPrompts.HYBRID_CLIENT_IMPLEMENTATION_PROMPT);
        verify(contextReducer).reduceImplementationPass(eq(ctx), eq(ImplementationSurface.CLIENT));
        assertThat(result.validatedOutput().has("manifest.json")).isTrue();
        assertThat(result.attemptsUsed()).isEqualTo(1);
    }

    @Test
    @DisplayName("CLI model executes a single generic LLM pass")
    void execute_cli_genericSinglePass() throws Exception {
        var spec = objectMapper.readTree("""
                {"runtime_environment":{"execution_model":"CLI"}}
                """);
        var ctx = MidasContext.start("Build CLI tool", "run-cli").withTechnicalSpec(spec);
        stubImplementationView("run-cli", ContextReducer.AgentRole.IMPLEMENTATION_ENGINEER);

        when(llmClient.call(any())).thenReturn(LlmCallResult.ofText("""
                {"cmd/main.go":"package main"}
                """));

        AgentResult result = coordinator.execute(ctx, "ImplementationEngineerAgent");

        ArgumentCaptor<LlmCallRequest> captor = ArgumentCaptor.forClass(LlmCallRequest.class);
        verify(llmClient, times(1)).call(captor.capture());
        assertThat(captor.getValue().getSystemPrompt())
                .isEqualTo(AgentSystemPrompts.IMPLEMENTATION_ENGINEER_PROMPT);
        verify(contextReducer).reduce(any(), eq(ContextReducer.AgentRole.IMPLEMENTATION_ENGINEER));
        assertThat(result.validatedOutput().has("cmd/main.go")).isTrue();
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

        when(contextReducer.reduceImplementationPass(eq(ctx), eq(ImplementationSurface.CLIENT)))
                .thenReturn(view("run-hybrid", "IMPLEMENTATION_ENGINEER_CLIENT"));
        when(contextReducer.reduceImplementationPass(eq(ctx), eq(ImplementationSurface.SERVER)))
                .thenReturn(view("run-hybrid", "IMPLEMENTATION_ENGINEER_SERVER"));

        when(llmClient.call(argThat(req -> req != null && req.getAgentName().endsWith("Client"))))
                .thenReturn(LlmCallResult.ofText("{\"manifest.json\":\"{}\", \"src/popup.ts\":\"export const ok = true;\"}"));
        when(llmClient.call(argThat(req -> req != null && req.getAgentName().endsWith("Server"))))
                .thenReturn(LlmCallResult.ofText("{\"src/main/java/com/example/App.java\":\"public class App {}\"}"));

        AgentResult result = coordinator.execute(ctx, "ImplementationEngineerAgent");

        verify(llmClient, times(2)).call(any(LlmCallRequest.class));
        assertThat(result.validatedOutput().size()).isEqualTo(3);
        assertThat(result.validatedOutput().has("manifest.json")).isTrue();
        assertThat(result.validatedOutput().has("src/main/java/com/example/App.java")).isTrue();
        assertThat(result.attemptsUsed()).isEqualTo(2);
    }

    @Test
    @DisplayName("HYBRID model uses dedicated client and server system prompts")
    void execute_hybrid_usesDedicatedPromptsForEachPass() throws Exception {
        var spec = objectMapper.readTree("""
                {"runtime_environment":{"execution_model":"HYBRID"}}
                """);
        var ctx = MidasContext.start("Build hybrid app", "run-hybrid").withTechnicalSpec(spec);

        when(contextReducer.reduceImplementationPass(eq(ctx), eq(ImplementationSurface.CLIENT)))
                .thenReturn(view("run-hybrid", "IMPLEMENTATION_ENGINEER_CLIENT"));
        when(contextReducer.reduceImplementationPass(eq(ctx), eq(ImplementationSurface.SERVER)))
                .thenReturn(view("run-hybrid", "IMPLEMENTATION_ENGINEER_SERVER"));
        when(llmClient.call(argThat(req -> req != null && req.getAgentName().endsWith("Client"))))
                .thenReturn(LlmCallResult.ofText("{\"manifest.json\":\"{}\"}"));
        when(llmClient.call(argThat(req -> req != null && req.getAgentName().endsWith("Server"))))
                .thenReturn(LlmCallResult.ofText("{\"App.java\":\"public class App {}\"}"));

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
        var ctx = MidasContext.start("Build hybrid app", "run-hybrid-fail").withTechnicalSpec(spec);

        when(contextReducer.reduceImplementationPass(eq(ctx), eq(ImplementationSurface.CLIENT)))
                .thenReturn(view("run-hybrid-fail", "IMPLEMENTATION_ENGINEER_CLIENT"));
        when(contextReducer.reduceImplementationPass(eq(ctx), eq(ImplementationSurface.SERVER)))
                .thenReturn(view("run-hybrid-fail", "IMPLEMENTATION_ENGINEER_SERVER"));
        when(llmClient.call(argThat(req -> req != null && req.getAgentName().endsWith("Client"))))
                .thenReturn(LlmCallResult.ofText("{\"manifest.json\":\"{}\"}"));
        when(llmClient.call(argThat(req -> req != null && req.getAgentName().endsWith("Server"))))
                .thenThrow(LlmCallException.emptyResponse("ImplementationEngineerAgentServer"));

        assertThatThrownBy(() -> coordinator.execute(ctx, "ImplementationEngineerAgent"))
                .isInstanceOf(LlmCallException.class);
    }

    @Test
    @DisplayName("CLI model appends PRODUCT REVIEW REMEDIATION block when directive is present")
    void execute_cli_withRemediationDirective_appendsRemediationBlock() throws Exception {
        var spec = objectMapper.readTree("""
                {"runtime_environment":{"execution_model":"CLI"}}
                """);
        var directive = objectMapper.readTree("""
                {"source_verdict":"REJECT","required_changes":["Add export command"]}
                """);
        var ctx = MidasContext.start("Build CLI tool", "run-remediate")
                .withTechnicalSpec(spec)
                .withRemediationDirective(directive);
        stubImplementationView("run-remediate", ContextReducer.AgentRole.IMPLEMENTATION_ENGINEER);

        when(llmClient.call(any())).thenReturn(LlmCallResult.ofText("""
                {"cmd/main.go":"package main"}
                """));

        coordinator.execute(ctx, "ImplementationEngineerAgent");

        ArgumentCaptor<LlmCallRequest> captor = ArgumentCaptor.forClass(LlmCallRequest.class);
        verify(llmClient, times(1)).call(captor.capture());
        assertThat(captor.getValue().getSystemPrompt())
                .startsWith(AgentSystemPrompts.IMPLEMENTATION_ENGINEER_PROMPT)
                .contains("--- PRODUCT REVIEW REMEDIATION ---")
                .contains("Add export command")
                .contains("Do NOT introduce new features");
    }

    @Test
    @DisplayName("CLI model without remediation directive uses base prompt unchanged")
    void execute_cli_withoutRemediationDirective_usesBasePrompt() throws Exception {
        var spec = objectMapper.readTree("""
                {"runtime_environment":{"execution_model":"CLI"}}
                """);
        var ctx = MidasContext.start("Build CLI tool", "run-cli").withTechnicalSpec(spec);
        stubImplementationView("run-cli", ContextReducer.AgentRole.IMPLEMENTATION_ENGINEER);

        when(llmClient.call(any())).thenReturn(LlmCallResult.ofText("""
                {"cmd/main.go":"package main"}
                """));

        coordinator.execute(ctx, "ImplementationEngineerAgent");

        ArgumentCaptor<LlmCallRequest> captor = ArgumentCaptor.forClass(LlmCallRequest.class);
        verify(llmClient, times(1)).call(captor.capture());
        assertThat(captor.getValue().getSystemPrompt())
                .isEqualTo(AgentSystemPrompts.IMPLEMENTATION_ENGINEER_PROMPT);
    }

    private void stubImplementationView(String runId, ContextReducer.AgentRole role) {
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

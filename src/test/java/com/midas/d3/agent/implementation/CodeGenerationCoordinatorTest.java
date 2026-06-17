package com.midas.d3.agent.implementation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.midas.d3.agent.AgentSystemPrompts;
import com.midas.d3.agent.base.AgentResult;
import com.midas.d3.config.JacksonConfig;
import com.midas.d3.context.AgentContextView;
import com.midas.d3.context.ContextReducer;
import com.midas.d3.context.MidasContext;
import com.midas.d3.llm.LlmCallRequest;
import com.midas.d3.llm.LlmClient;
import com.midas.d3.statemachine.MidasState;
import com.midas.d3.statemachine.ValidatorRegistry;
import com.midas.d3.validation.GoalKeeperValidator;
import com.midas.d3.validation.ImplementationEngineerValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CodeGenerationCoordinator")
class CodeGenerationCoordinatorTest {

    @Mock private ContextReducer contextReducer;
    @Mock private LlmClient llmClient;
    @Mock private ValidatorRegistry validatorRegistry;

    private ObjectMapper objectMapper;
    private ImplementationEngineerValidator validator;
    private CodeGenerationCoordinator coordinator;

    @BeforeEach
    void setUp() {
        objectMapper = new JacksonConfig().objectMapper();
        validator = new ImplementationEngineerValidator(objectMapper);
        coordinator = new CodeGenerationCoordinator(
                contextReducer, llmClient, validatorRegistry, objectMapper);
        when(validatorRegistry.getValidator(MidasState.CODE_GENERATION))
                .thenReturn(Optional.of(validator));
    }

    @Test
    @DisplayName("non-HYBRID model executes a single LLM pass")
    void execute_nonHybrid_singlePass() throws Exception {
        var spec = objectMapper.readTree("""
                {"runtime_environment":{"execution_model":"SERVER_SIDE"}}
                """);
        var ctx = MidasContext.start("Build API", "run-001").withTechnicalSpec(spec);
        stubImplementationView("run-001", ContextReducer.AgentRole.IMPLEMENTATION_ENGINEER);

        when(llmClient.call(any())).thenReturn("""
                {"src/main/java/com/example/App.java":"public class App {}"}
                """);

        AgentResult result = coordinator.execute(ctx, "ImplementationEngineerAgent");

        verify(llmClient, times(1)).call(any());
        assertThat(result.validatedOutput().has("src/main/java/com/example/App.java")).isTrue();
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

        when(llmClient.call(any()))
                .thenReturn("{\"manifest.json\":\"{}\", \"src/popup.ts\":\"export const ok = true;\"}")
                .thenReturn("{\"src/main/java/com/example/App.java\":\"public class App {}\"}");

        AgentResult result = coordinator.execute(ctx, "ImplementationEngineerAgent");

        verify(llmClient, times(2)).call(any(LlmCallRequest.class));
        assertThat(result.validatedOutput().size()).isEqualTo(3);
        assertThat(result.validatedOutput().has("manifest.json")).isTrue();
        assertThat(result.validatedOutput().has("src/main/java/com/example/App.java")).isTrue();
        assertThat(result.attemptsUsed()).isEqualTo(2);
    }

    @Test
    @DisplayName("HYBRID client pass uses dedicated client system prompt")
    void execute_hybrid_usesClientPromptForFirstPass() throws Exception {
        var spec = objectMapper.readTree("""
                {"runtime_environment":{"execution_model":"HYBRID"}}
                """);
        var ctx = MidasContext.start("Build hybrid app", "run-hybrid").withTechnicalSpec(spec);

        when(contextReducer.reduceImplementationPass(eq(ctx), eq(ImplementationSurface.CLIENT)))
                .thenReturn(view("run-hybrid", "IMPLEMENTATION_ENGINEER_CLIENT"));
        when(contextReducer.reduceImplementationPass(eq(ctx), eq(ImplementationSurface.SERVER)))
                .thenReturn(view("run-hybrid", "IMPLEMENTATION_ENGINEER_SERVER"));
        when(llmClient.call(any()))
                .thenReturn("{\"manifest.json\":\"{}\"}")
                .thenReturn("{\"App.java\":\"public class App {}\"}");

        coordinator.execute(ctx, "ImplementationEngineerAgent");

        ArgumentCaptor<LlmCallRequest> captor = ArgumentCaptor.forClass(LlmCallRequest.class);
        verify(llmClient, times(2)).call(captor.capture());
        assertThat(captor.getAllValues().get(0).getSystemPrompt())
                .isEqualTo(AgentSystemPrompts.HYBRID_CLIENT_IMPLEMENTATION_PROMPT);
        assertThat(captor.getAllValues().get(1).getSystemPrompt())
                .isEqualTo(AgentSystemPrompts.HYBRID_SERVER_IMPLEMENTATION_PROMPT);
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

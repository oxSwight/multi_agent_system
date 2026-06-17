package com.midas.d3.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.midas.d3.config.JacksonConfig;
import com.midas.d3.agent.base.AgentResult;
import com.midas.d3.agent.implementation.CodeGenerationCoordinator;
import com.midas.d3.context.ContextReducer;
import com.midas.d3.llm.LlmCallException;
import com.midas.d3.llm.LlmCallRequest;
import com.midas.d3.llm.LlmClient;
import com.midas.d3.statemachine.MidasState;
import com.midas.d3.statemachine.PipelineOrchestrator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link AgentOrchestrationService}.
 * All external dependencies are mocked — no Spring context required.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentOrchestrationService Tests")
class AgentOrchestrationServiceTest {

    @Mock private PipelineOrchestrator  pipelineOrchestrator;
    @Mock private ContextReducer        contextReducer;
    @Mock private LlmClient             llmClient;
    @Mock private AgentSystemPrompts    agentSystemPrompts;
    @Mock private CodeGenerationCoordinator codeGenerationCoordinator;

    private ObjectMapper objectMapper;
    private AgentOrchestrationService service;

    private static final String RUN_ID     = "test-run-123";
    private static final String SYSTEM_PROMPT =
            "You are a System Analyst. Output only JSON.";
    private static final String VALID_LLM_OUTPUT = """
            {
              "business_goal": "Build a task manager",
              "core_features": ["Create task", "Delete task"],
              "edge_cases_and_handling": [{"case": "empty", "solution": "400"}],
              "performance_constraints": ["<200ms"]
            }
            """;

    @BeforeEach
    void setUp() {
        objectMapper = new JacksonConfig().objectMapper();
        service = new AgentOrchestrationService(
                pipelineOrchestrator, contextReducer, llmClient, agentSystemPrompts,
                objectMapper, codeGenerationCoordinator);
    }

    // ── Happy path ────────────────────────────────────────────────────────────

    @Nested
    class HappyPath {

        @BeforeEach
        void mockSuccessPath() {
            when(pipelineOrchestrator.getState(RUN_ID)).thenReturn(MidasState.SYSTEM_ANALYSIS);
            when(pipelineOrchestrator.getContext(RUN_ID)).thenReturn(
                    Optional.of(com.midas.d3.context.MidasContext.start("Build a task manager", RUN_ID)));
            when(contextReducer.reduce(any(), eq(ContextReducer.AgentRole.SYSTEM_ANALYST)))
                    .thenReturn(com.midas.d3.context.AgentContextView.builder()
                            .agentName("SYSTEM_ANALYST")
                            .pipelineRunId(RUN_ID)
                            .rawUserIdea("Build a task manager")
                            .requiredArtifacts(java.util.Collections.emptyMap())
                            .estimatedTokenBudget(100)
                            .build());
            when(agentSystemPrompts.getPrompt(MidasState.SYSTEM_ANALYSIS))
                    .thenReturn(Optional.of(SYSTEM_PROMPT));
            when(llmClient.call(any())).thenReturn(VALID_LLM_OUTPUT);
            when(pipelineOrchestrator.getState(RUN_ID))
                    .thenReturn(MidasState.SYSTEM_ANALYSIS) // first call
                    .thenReturn(MidasState.ARCHITECTURE_DESIGN); // after submit
        }

        @Test
        @DisplayName("runCurrentStage calls LLM, submits sanitized result, returns new state")
        void runCurrentStage_happyPath_advancesState() throws LlmCallException {
            MidasState result = service.runCurrentStage(RUN_ID);

            assertThat(result).isEqualTo(MidasState.ARCHITECTURE_DESIGN);
            verify(llmClient).call(any(LlmCallRequest.class));
            verify(pipelineOrchestrator).submitResult(eq(RUN_ID), anyString());
        }

        @Test
        @DisplayName("LLM output wrapped in markdown fence is sanitized before submission")
        void runCurrentStage_markdownWrappedOutput_sanitizedBeforeSubmit() throws LlmCallException {
            when(llmClient.call(any())).thenReturn("```json\n" + VALID_LLM_OUTPUT.strip() + "\n```");

            service.runCurrentStage(RUN_ID);

            ArgumentCaptor<String> submittedCaptor = ArgumentCaptor.forClass(String.class);
            verify(pipelineOrchestrator).submitResult(eq(RUN_ID), submittedCaptor.capture());
            String submitted = submittedCaptor.getValue();
            assertThat(submitted).doesNotContain("```");
            assertThat(submitted).startsWith("{");
        }

        @Test
        @DisplayName("LLM request contains correct system prompt and agentName")
        void runCurrentStage_llmRequestHasCorrectPrompt() throws LlmCallException {
            service.runCurrentStage(RUN_ID);

            ArgumentCaptor<LlmCallRequest> captor = ArgumentCaptor.forClass(LlmCallRequest.class);
            verify(llmClient).call(captor.capture());
            LlmCallRequest req = captor.getValue();
            assertThat(req.getSystemPrompt()).isEqualTo(SYSTEM_PROMPT);
            assertThat(req.getAgentName()).isEqualTo("SystemAnalyst");
            assertThat(req.getPipelineRunId()).isEqualTo(RUN_ID);
            assertThat(req.getStage()).isEqualTo(MidasState.SYSTEM_ANALYSIS);
        }
    }

    // ── Error cases ───────────────────────────────────────────────────────────

    @Nested
    class ErrorCases {

        @Test
        @DisplayName("Throws IllegalArgumentException for non-processing state (e.g. COMPLETED)")
        void runCurrentStage_completedState_throwsIllegalArgument() {
            when(pipelineOrchestrator.getState(RUN_ID)).thenReturn(MidasState.COMPLETED);
            assertThatThrownBy(() -> service.runCurrentStage(RUN_ID))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("COMPLETED");
        }

        @Test
        @DisplayName("Propagates LlmCallException from client")
        void runCurrentStage_llmThrows_propagatesException() throws LlmCallException {
            when(pipelineOrchestrator.getState(RUN_ID)).thenReturn(MidasState.SYSTEM_ANALYSIS);
            when(pipelineOrchestrator.getContext(RUN_ID)).thenReturn(
                    Optional.of(com.midas.d3.context.MidasContext.start("idea", RUN_ID)));
            when(contextReducer.reduce(any(), any()))
                    .thenReturn(com.midas.d3.context.AgentContextView.builder()
                            .agentName("SYSTEM_ANALYST").pipelineRunId(RUN_ID)
                            .rawUserIdea("idea").requiredArtifacts(java.util.Collections.emptyMap())
                            .estimatedTokenBudget(10).build());
            when(agentSystemPrompts.getPrompt(any())).thenReturn(Optional.of("prompt"));
            when(llmClient.call(any())).thenThrow(LlmCallException.timeout("SystemAnalyst"));

            assertThatThrownBy(() -> service.runCurrentStage(RUN_ID))
                    .isInstanceOf(LlmCallException.class)
                    .hasMessageContaining("timed out");
        }

        @Test
        @DisplayName("Throws IllegalStateException when context not found")
        void runCurrentStage_contextNotFound_throwsIllegalState() {
            when(pipelineOrchestrator.getState(RUN_ID)).thenReturn(MidasState.SYSTEM_ANALYSIS);
            when(pipelineOrchestrator.getContext(RUN_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.runCurrentStage(RUN_ID))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("MidasContext not found");
        }
    }

    // ── CODE_GENERATION / HYBRID fan-out ────────────────────────────────────

    @Nested
    class CodeGenerationStage {

        @Test
        @DisplayName("CODE_GENERATION delegates to CodeGenerationCoordinator instead of direct LLM call")
        void runCurrentStage_codeGeneration_usesCoordinator() throws Exception {
            when(pipelineOrchestrator.getState(RUN_ID)).thenReturn(MidasState.CODE_GENERATION);
            when(pipelineOrchestrator.getContext(RUN_ID)).thenReturn(
                    Optional.of(com.midas.d3.context.MidasContext.start("Build hybrid", RUN_ID)));
            when(codeGenerationCoordinator.execute(any(), eq("ImplementationEngineer")))
                    .thenReturn(new AgentResult(
                            new JacksonConfig().objectMapper().createObjectNode(),
                            "{\"App.java\":\"class App {}\"}",
                            2));
            when(pipelineOrchestrator.getState(RUN_ID))
                    .thenReturn(MidasState.CODE_GENERATION)
                    .thenReturn(MidasState.TEST_GENERATION);

            MidasState result = service.runCurrentStage(RUN_ID);

            assertThat(result).isEqualTo(MidasState.TEST_GENERATION);
            verify(codeGenerationCoordinator).execute(any(), eq("ImplementationEngineer"));
            verify(llmClient, never()).call(any());
            verify(pipelineOrchestrator).submitResult(eq(RUN_ID), eq("{\"App.java\":\"class App {}\"}"));
        }
    }

    // ── buildUserMessage ──────────────────────────────────────────────────────

    @Nested
    class BuildUserMessageTests {

        @Test
        @DisplayName("User message contains raw idea and TASK instruction")
        void buildUserMessage_containsIdeaAndTask() {
            var view = com.midas.d3.context.AgentContextView.builder()
                    .agentName("SYSTEM_ANALYST").pipelineRunId(RUN_ID)
                    .rawUserIdea("Build a todo app")
                    .requiredArtifacts(java.util.Collections.emptyMap())
                    .estimatedTokenBudget(50).build();

            String msg = service.buildUserMessage(view, MidasState.SYSTEM_ANALYSIS);

            assertThat(msg).contains("Build a todo app");
            assertThat(msg).contains("TASK:");
            assertThat(msg).contains("SystemAnalyst");
        }

        @Test
        @DisplayName("User message includes upstream artifacts when present")
        void buildUserMessage_withArtifacts_includesArtifactSection() throws Exception {
            var spec = objectMapper.readTree("{\"business_goal\": \"Build app\"}");
            var view = com.midas.d3.context.AgentContextView.builder()
                    .agentName("SOFTWARE_ARCHITECT").pipelineRunId(RUN_ID)
                    .rawUserIdea("Build a todo app")
                    .requiredArtifacts(java.util.Map.of("technicalSpec", spec))
                    .estimatedTokenBudget(200).build();

            String msg = service.buildUserMessage(view, MidasState.ARCHITECTURE_DESIGN);

            assertThat(msg).contains("UPSTREAM CONTEXT:");
            assertThat(msg).contains("Technical Specification");
            assertThat(msg).contains("business_goal");
        }
    }

    // ── isProcessingStage ─────────────────────────────────────────────────────

    @Test
    @DisplayName("isProcessingStage returns true for all 7 processing states")
    void isProcessingStage_allProcessingStates() {
        assertThat(service.isProcessingStage(MidasState.SYSTEM_ANALYSIS)).isTrue();
        assertThat(service.isProcessingStage(MidasState.ARCHITECTURE_DESIGN)).isTrue();
        assertThat(service.isProcessingStage(MidasState.INTEGRATION_STRATEGY)).isTrue();
        assertThat(service.isProcessingStage(MidasState.CODE_GENERATION)).isTrue();
        assertThat(service.isProcessingStage(MidasState.TEST_GENERATION)).isTrue();
        assertThat(service.isProcessingStage(MidasState.SECOPS_AUDIT)).isTrue();
        assertThat(service.isProcessingStage(MidasState.PRODUCT_REVIEW)).isTrue();
    }

    @Test
    @DisplayName("isProcessingStage returns false for terminal and entry states")
    void isProcessingStage_nonProcessingStates() {
        assertThat(service.isProcessingStage(MidasState.IDLE)).isFalse();
        assertThat(service.isProcessingStage(MidasState.COMPLETED)).isFalse();
        assertThat(service.isProcessingStage(MidasState.ERROR)).isFalse();
        assertThat(service.isProcessingStage(null)).isFalse();
    }
}

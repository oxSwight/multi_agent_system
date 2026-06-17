package com.midas.d3.statemachine;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for {@link PipelineOrchestrator}.
 */
@SpringBootTest
@ActiveProfiles("test")
class PipelineOrchestratorTest {

    @Autowired
    private PipelineOrchestrator orchestrator;

    @Test
    void startPipeline_returnsRunId_andStateIsSystemAnalysis() {
        String runId = orchestrator.startPipeline("Build a booking system");

        assertThat(runId).isNotBlank();
        assertThat(orchestrator.getState(runId)).isEqualTo(MidasState.SYSTEM_ANALYSIS);

        orchestrator.reset(runId);
    }

    @Test
    void startPipeline_withBlankIdea_throwsIllegalArgument() {
        assertThatThrownBy(() -> orchestrator.startPipeline("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void startPipeline_withNullIdea_throwsIllegalArgument() {
        assertThatThrownBy(() -> orchestrator.startPipeline(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void getState_unknownRunId_throwsPipelineNotFoundException() {
        assertThatThrownBy(() -> orchestrator.getState("non-existent-run-id"))
                .isInstanceOf(PipelineOrchestrator.PipelineNotFoundException.class)
                .hasMessageContaining("non-existent-run-id");
    }

    @Test
    void getContext_returnsContextAfterStart() {
        String runId = orchestrator.startPipeline("Build a CRM");
        assertThat(orchestrator.getContext(runId)).isPresent();
        assertThat(orchestrator.getContext(runId).get().getRawUserIdea()).isEqualTo("Build a CRM");

        orchestrator.reset(runId);
    }

    @Test
    void reset_removesRunFromActiveRegistry() {
        String runId = orchestrator.startPipeline("Build something");

        orchestrator.reset(runId);

        assertThatThrownBy(() -> orchestrator.getState(runId))
                .isInstanceOf(PipelineOrchestrator.PipelineNotFoundException.class);
    }

    @Test
    void submitResult_withNullRunId_throwsIllegalArgument() {
        assertThatThrownBy(() -> orchestrator.submitResult(null, "{}"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void submitResult_invalidJson_incrementsRetry() {
        String runId = orchestrator.startPipeline("Build a scheduling app");

        orchestrator.submitResult(runId, "{ invalid json }");

        assertThat(orchestrator.getContext(runId))
                .isPresent()
                .hasValueSatisfying(ctx ->
                        assertThat(ctx.getValidationRetries()).isEqualTo(1));

        orchestrator.reset(runId);
    }
}

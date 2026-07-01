package com.midas.d3.statemachine.action;

import com.midas.d3.context.MidasContext;
import com.midas.d3.statemachine.MidasEvent;
import com.midas.d3.statemachine.MidasState;
import com.midas.d3.statemachine.PipelineContextKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.statemachine.ExtendedState;
import org.springframework.statemachine.StateContext;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link InitContextAction} — focused on the START-transition cleanup that keeps a
 * machine reused via {@code RESET → START} from leaking a prior run's completion latch or
 * graceful-degradation state into a fresh run.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InitContextAction Tests")
class InitContextActionTest {

    @Mock private StateContext<MidasState, MidasEvent> stateContext;
    @Mock private ExtendedState                        extendedState;

    private InitContextAction action;

    @BeforeEach
    void setUp() {
        action = new InitContextAction();
        when(stateContext.getExtendedState()).thenReturn(extendedState);
    }

    @Test
    @DisplayName("START creates a fresh context and clears residual completion + degradation state (machine reuse)")
    void execute_clearsResidualDegradationState_onReuse() {
        when(stateContext.getMessageHeader(PipelineContextKeys.RAW_IDEA_HEADER)).thenReturn("Build a fresh app");
        when(stateContext.getMessageHeader(PipelineContextKeys.RUN_ID_HEADER)).thenReturn("run-reuse-002");

        // Simulate a machine reused via RESET → START after a prior run degraded: the prior run's
        // completion latch AND its degradation flag/payload are still sitting in ExtendedState.
        Map<Object, Object> vars = new HashMap<>();
        vars.put(PipelineContextKeys.ARTIFACT_DELIVERY_INITIATED, Boolean.TRUE);
        vars.put(PipelineContextKeys.DEGRADED_COMPLETION, Boolean.TRUE);
        vars.put(PipelineContextKeys.DEGRADATION_PARTIAL_SOURCE, "stale-partial");
        vars.put(PipelineContextKeys.DEGRADATION_FEATURE_MANIFEST, "stale-manifest");
        vars.put(PipelineContextKeys.DEGRADATION_GAPS, List.of("stale gap"));
        when(extendedState.getVariables()).thenReturn(vars);

        action.execute(stateContext);

        MidasContext fresh = (MidasContext) vars.get(PipelineContextKeys.MIDAS_CONTEXT);
        assertThat(fresh).isNotNull();
        assertThat(fresh.getPipelineRunId()).isEqualTo("run-reuse-002");
        assertThat(fresh.getRawUserIdea()).isEqualTo("Build a fresh app");

        // A fresh run must NOT carry the prior degraded run's terminal state or partial payload —
        // otherwise the next clean COMPLETED would persist via completeRunWithGaps() instead of
        // completeRun() (client-visible status corruption).
        assertThat(vars).doesNotContainKeys(
                PipelineContextKeys.ARTIFACT_DELIVERY_INITIATED,
                PipelineContextKeys.DEGRADED_COMPLETION,
                PipelineContextKeys.DEGRADATION_PARTIAL_SOURCE,
                PipelineContextKeys.DEGRADATION_FEATURE_MANIFEST,
                PipelineContextKeys.DEGRADATION_GAPS);
    }

    @Test
    @DisplayName("Missing rawIdea header → fallback context with MISSING_IDEA sentinel")
    void execute_missingIdea_usesFallbackSentinel() {
        // No message headers stubbed → all getMessageHeader(...) return null.
        Map<Object, Object> vars = new HashMap<>();
        when(extendedState.getVariables()).thenReturn(vars);

        action.execute(stateContext);

        MidasContext ctx = (MidasContext) vars.get(PipelineContextKeys.MIDAS_CONTEXT);
        assertThat(ctx).isNotNull();
        assertThat(ctx.getRawUserIdea()).isEqualTo("MISSING_IDEA");
        assertThat(ctx.getPipelineRunId()).isNotBlank();
    }
}

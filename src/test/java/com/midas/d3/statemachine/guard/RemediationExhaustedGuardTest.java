package com.midas.d3.statemachine.guard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.midas.d3.context.MidasContext;
import com.midas.d3.statemachine.MidasEvent;
import com.midas.d3.statemachine.MidasState;
import com.midas.d3.statemachine.PipelineContextKeys;
import com.midas.d3.statemachine.PipelineTopology;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.statemachine.ExtendedState;
import org.springframework.statemachine.StateContext;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("RemediationExhaustedGuard")
class RemediationExhaustedGuardTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final PipelineTopology topology = new PipelineTopology();
    private final RemediationExhaustedGuard guard = new RemediationExhaustedGuard(topology);

    private Map<Object, Object> vars;
    private StateContext<MidasState, MidasEvent> context;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        vars = new HashMap<>();
        context = mock(StateContext.class);
        ExtendedState extendedState = mock(ExtendedState.class);
        when(context.getExtendedState()).thenReturn(extendedState);
        when(extendedState.getVariables()).thenReturn(vars);
    }

    private JsonNode node(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("REJECT at PRODUCT_REVIEW with exhausted attempts → true")
    void rejectWithExhaustedAttempts_returnsTrue() {
        vars.put(PipelineContextKeys.PENDING_STAGE, MidasState.PRODUCT_REVIEW);
        vars.put(PipelineContextKeys.LAST_VALIDATED_NODE, node("{\"verdict\":\"REJECT\"}"));
        vars.put(PipelineContextKeys.MIDAS_CONTEXT,
                MidasContext.start("idea", "run-001").withProductReviewRemediationAttempts(1));

        assertThat(guard.evaluate(context)).isTrue();
    }

    @Test
    @DisplayName("REJECT at PRODUCT_REVIEW with zero attempts → false")
    void rejectWithAttemptsRemaining_returnsFalse() {
        vars.put(PipelineContextKeys.PENDING_STAGE, MidasState.PRODUCT_REVIEW);
        vars.put(PipelineContextKeys.LAST_VALIDATED_NODE, node("{\"verdict\":\"REJECT\"}"));
        vars.put(PipelineContextKeys.MIDAS_CONTEXT, MidasContext.start("idea", "run-001"));

        assertThat(guard.evaluate(context)).isFalse();
    }

    @Test
    @DisplayName("PASS verdict → false")
    void passVerdict_returnsFalse() {
        vars.put(PipelineContextKeys.PENDING_STAGE, MidasState.PRODUCT_REVIEW);
        vars.put(PipelineContextKeys.LAST_VALIDATED_NODE, node("{\"verdict\":\"PASS\"}"));
        vars.put(PipelineContextKeys.MIDAS_CONTEXT,
                MidasContext.start("idea", "run-001").withProductReviewRemediationAttempts(1));

        assertThat(guard.evaluate(context)).isFalse();
    }
}

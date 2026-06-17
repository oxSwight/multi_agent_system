package com.midas.d3.statemachine.guard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.midas.d3.statemachine.MidasEvent;
import com.midas.d3.statemachine.MidasState;
import com.midas.d3.statemachine.PipelineContextKeys;
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

/**
 * Unit tests for {@link ProductReviewRejectedGuard} — the routing guard that turns a well-formed
 * {@code REJECT} verdict at the PRODUCT_REVIEW gate into a terminal ERROR transition.
 */
@DisplayName("ProductReviewRejectedGuard — verdict routing")
class ProductReviewRejectedGuardTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ProductReviewRejectedGuard guard = new ProductReviewRejectedGuard();

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
    @DisplayName("REJECT verdict at PRODUCT_REVIEW → true (routes to ERROR)")
    void rejectVerdict_returnsTrue() {
        vars.put(PipelineContextKeys.PENDING_STAGE, MidasState.PRODUCT_REVIEW);
        vars.put(PipelineContextKeys.LAST_VALIDATED_NODE, node("{\"verdict\":\"REJECT\"}"));
        assertThat(guard.evaluate(context)).isTrue();
    }

    @Test
    @DisplayName("PASS verdict → false (advances normally)")
    void passVerdict_returnsFalse() {
        vars.put(PipelineContextKeys.PENDING_STAGE, MidasState.PRODUCT_REVIEW);
        vars.put(PipelineContextKeys.LAST_VALIDATED_NODE, node("{\"verdict\":\"PASS\"}"));
        assertThat(guard.evaluate(context)).isFalse();
    }

    @Test
    @DisplayName("PASS_WITH_NOTES verdict → false")
    void passWithNotesVerdict_returnsFalse() {
        vars.put(PipelineContextKeys.PENDING_STAGE, MidasState.PRODUCT_REVIEW);
        vars.put(PipelineContextKeys.LAST_VALIDATED_NODE, node("{\"verdict\":\"PASS_WITH_NOTES\"}"));
        assertThat(guard.evaluate(context)).isFalse();
    }

    @Test
    @DisplayName("REJECT verdict but pending stage is NOT PRODUCT_REVIEW → false (scoped no-op)")
    void rejectVerdict_wrongStage_returnsFalse() {
        vars.put(PipelineContextKeys.PENDING_STAGE, MidasState.SECOPS_AUDIT);
        vars.put(PipelineContextKeys.LAST_VALIDATED_NODE, node("{\"verdict\":\"REJECT\"}"));
        assertThat(guard.evaluate(context)).isFalse();
    }

    @Test
    @DisplayName("No validated node (schema failed) → false (let retry/exhaustion handle it)")
    void noValidatedNode_returnsFalse() {
        vars.put(PipelineContextKeys.PENDING_STAGE, MidasState.PRODUCT_REVIEW);
        assertThat(guard.evaluate(context)).isFalse();
    }
}

package com.midas.d3.statemachine.guard;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.midas.d3.context.MidasContext;
import com.midas.d3.statemachine.MidasEvent;
import com.midas.d3.statemachine.MidasState;
import com.midas.d3.statemachine.PipelineContextKeys;
import com.midas.d3.statemachine.PipelineTopology;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.statemachine.StateContext;
import org.springframework.statemachine.support.DefaultExtendedState;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("SkipIntegrationStageGuard")
class SkipIntegrationStageGuardTest {

    private final PipelineTopology topology = new PipelineTopology();
    private SkipIntegrationStageGuard guard;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        guard = new SkipIntegrationStageGuard(topology);
        objectMapper = new ObjectMapper();
    }

    @Test
    @DisplayName("returns true when validated architecture explicitly disables external integrations")
    void evaluate_trueWhenArchitectureFlagsFalse() throws Exception {
        var validated = objectMapper.readTree("""
                {"architecture_style":"CLIENT_ONLY","has_external_integrations":false}
                """);
        var ctx = MidasContext.start("Build a tool", "run-001");

        assertThat(guard.evaluate(context(validated, ctx))).isTrue();
    }

    @Test
    @DisplayName("returns false when validated architecture omits the skip flag")
    void evaluate_falseWhenFlagAbsent() throws Exception {
        var validated = objectMapper.readTree("""
                {"architecture_style":"CLIENT_SERVER"}
                """);
        var ctx = MidasContext.start("Build SaaS", "run-001");

        assertThat(guard.evaluate(context(validated, ctx))).isFalse();
    }

    @Test
    @DisplayName("returns false when validation did not produce a node")
    void evaluate_falseWhenValidatedNodeMissing() {
        var ctx = MidasContext.start("Build SaaS", "run-001");
        assertThat(guard.evaluate(context(null, ctx))).isFalse();
    }

    @SuppressWarnings("unchecked")
    private StateContext<MidasState, MidasEvent> context(
            com.fasterxml.jackson.databind.JsonNode validated,
            MidasContext midasContext) {
        Map<Object, Object> vars = new HashMap<>();
        if (validated != null) {
            vars.put(PipelineContextKeys.LAST_VALIDATED_NODE, validated);
        }
        vars.put(PipelineContextKeys.MIDAS_CONTEXT, midasContext);

        DefaultExtendedState extendedState = new DefaultExtendedState(vars);
        StateContext<MidasState, MidasEvent> stateContext = mock(StateContext.class);
        when(stateContext.getExtendedState()).thenReturn(extendedState);
        return stateContext;
    }
}

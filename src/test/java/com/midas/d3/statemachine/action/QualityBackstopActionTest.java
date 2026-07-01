package com.midas.d3.statemachine.action;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.midas.d3.config.JacksonConfig;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("QualityBackstopAction")
class QualityBackstopActionTest {

    @Mock private StoreArtifactAction storeArtifactAction;
    @Mock private StateContext<MidasState, MidasEvent> stateContext;
    @Mock private ExtendedState extendedState;

    private ObjectMapper objectMapper;
    private QualityBackstopAction action;
    private Map<Object, Object> vars;

    @BeforeEach
    void setUp() {
        objectMapper = new JacksonConfig().objectMapper();
        action = new QualityBackstopAction(storeArtifactAction);
        vars = new HashMap<>();
        when(stateContext.getExtendedState()).thenReturn(extendedState);
        when(extendedState.getVariables()).thenReturn(vars);
    }

    @Test
    @DisplayName("rewrites REJECT → PASS_WITH_NOTES (keeping the original summary) and delegates to StoreArtifactAction")
    void execute_rewritesVerdictAndDelegates() throws Exception {
        JsonNode reject = objectMapper.readTree("""
                {"verdict":"REJECT","summary":"missing CRUD operations",
                 "coverage_matrix":[{"requested_feature":"CRUD","status":"MISSING","evidence":"x"}],
                 "remediation_block":{"required_changes":["Implement CRUD"]}}
                """);
        vars.put(PipelineContextKeys.LAST_VALIDATED_NODE, reject);
        vars.put(PipelineContextKeys.MIDAS_CONTEXT, MidasContext.start("idea", "run-bs-1"));

        action.execute(stateContext);

        JsonNode after = (JsonNode) vars.get(PipelineContextKeys.LAST_VALIDATED_NODE);
        assertThat(after.get("verdict").asText()).isEqualTo("PASS_WITH_NOTES");
        assertThat(after.get("summary").asText()).contains("backstop").contains("missing CRUD operations");
        // advisory record preserved
        assertThat(after.get("remediation_block").get("required_changes")).hasSize(1);
        verify(storeArtifactAction).execute(stateContext);
    }
}

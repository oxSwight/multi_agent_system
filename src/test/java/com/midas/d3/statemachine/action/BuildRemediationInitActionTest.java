package com.midas.d3.statemachine.action;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.midas.d3.config.JacksonConfig;
import com.midas.d3.context.MidasContext;
import com.midas.d3.statemachine.AgentDispatcher;
import com.midas.d3.statemachine.MidasEvent;
import com.midas.d3.statemachine.MidasState;
import com.midas.d3.statemachine.PipelineContextKeys;
import com.midas.d3.statemachine.PipelineTopology;
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
import static org.mockito.Mockito.lenient;

/**
 * Unit tests for {@link BuildRemediationInitAction} — focused on the phase-aware remediation
 * directive: a {@code TEST}-phase failure must tell the agent its code compiled but its tests fail,
 * not to "fix the compile error."
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BuildRemediationInitAction — phase-aware remediation directive")
class BuildRemediationInitActionTest {

    @Mock private PipelineTopology topology;
    @Mock private AgentDispatcher agentDispatcher;
    @Mock private StateContext<MidasState, MidasEvent> stateContext;
    @Mock private ExtendedState extendedState;

    private ObjectMapper objectMapper;
    private BuildRemediationInitAction action;
    private Map<Object, Object> vars;

    @BeforeEach
    void setUp() {
        objectMapper = new JacksonConfig().objectMapper();
        action = new BuildRemediationInitAction(objectMapper, topology, agentDispatcher);
        vars = new HashMap<>();
        lenient().when(stateContext.getExtendedState()).thenReturn(extendedState);
        lenient().when(extendedState.getVariables()).thenReturn(vars);
        lenient().when(topology.maxBuildRemediations()).thenReturn(3);
    }

    private JsonNode runWith(String reportJson) throws Exception {
        vars.put(PipelineContextKeys.MIDAS_CONTEXT, MidasContext.start("Build app", "run-remediate-001"));
        vars.put(PipelineContextKeys.LAST_VALIDATED_NODE, objectMapper.readTree(reportJson));

        action.execute(stateContext);

        MidasContext updated = (MidasContext) vars.get(PipelineContextKeys.MIDAS_CONTEXT);
        return updated.getRemediationDirective();
    }

    @Test
    @DisplayName("TEST-phase failure → 'compiled but tests fail' instruction, never 'compile error'")
    void testPhase_instructsTestRemediation() throws Exception {
        JsonNode directive = runWith("""
                {
                  "build_status": "FAILED",
                  "failure_phase": "TEST",
                  "tool": "MAVEN",
                  "diagnostics": [],
                  "raw_output_tail": "Tests run: 3, Failures: 1"
                }
                """);

        assertThat(directive.get("failure_phase").asText()).isEqualTo("TEST");
        String instruction = directive.get("instruction").asText();
        assertThat(instruction).contains("COMPILED").contains("tests FAILED");
        assertThat(instruction).doesNotContain("FAILED to compile");
    }

    @Test
    @DisplayName("COMPILE-phase failure → 'fix the compile error' instruction (back-compatible)")
    void compilePhase_instructsCompileRemediation() throws Exception {
        JsonNode directive = runWith("""
                {
                  "build_status": "FAILED",
                  "failure_phase": "COMPILE",
                  "tool": "MAVEN",
                  "diagnostics": [],
                  "raw_output_tail": "error: cannot find symbol"
                }
                """);

        assertThat(directive.get("failure_phase").asText()).isEqualTo("COMPILE");
        assertThat(directive.get("instruction").asText()).contains("FAILED to compile");
    }

    @Test
    @DisplayName("Missing failure_phase defaults to COMPILE (an older report still remediates)")
    void absentPhase_defaultsToCompile() throws Exception {
        JsonNode directive = runWith("""
                {
                  "build_status": "FAILED",
                  "tool": "MAVEN",
                  "diagnostics": [],
                  "raw_output_tail": "boom"
                }
                """);

        assertThat(directive.get("failure_phase").asText()).isEqualTo("COMPILE");
        assertThat(directive.get("instruction").asText()).contains("FAILED to compile");
    }
}

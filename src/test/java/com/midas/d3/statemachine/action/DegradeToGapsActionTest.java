package com.midas.d3.statemachine.action;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Unit tests for {@link DegradeToGapsAction} — the {@code CODE_GENERATION → COMPLETED_WITH_GAPS}
 * transition action of the graceful-degradation path.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DegradeToGapsAction Tests")
class DegradeToGapsActionTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Mock private StateContext<MidasState, MidasEvent> stateContext;
    @Mock private ExtendedState                        extendedState;

    private DegradeToGapsAction action;

    @BeforeEach
    void setUp() {
        action = new DegradeToGapsAction(mapper);
        when(stateContext.getExtendedState()).thenReturn(extendedState);
    }

    @Test
    @DisplayName("Stores partial source + honest coverage report, flags DEGRADED_COMPLETION, consumes payload")
    void execute_buildsCoverageReport_andFlagsDegradedCompletion() throws Exception {
        MidasContext base = MidasContext.start("Build a task app", "run-degrade-001")
                .withLastErrorMessage("stale error from a prior failed attempt");
        JsonNode partial = mapper.readTree(
                "{\"src/main/java/A.java\":\"class A {}\",\"src/main/java/B.java\":\"class B {}\"}");
        JsonNode manifest = mapper.readTree(
                "[{\"feature_name\":\"Create task\"},{\"feature_name\":\"List tasks\"}]");
        List<String> gaps = List.of("Assign task not implemented", "Track progress not implemented");

        Map<Object, Object> vars = new HashMap<>();
        vars.put(PipelineContextKeys.MIDAS_CONTEXT, base);
        vars.put(PipelineContextKeys.DEGRADATION_PARTIAL_SOURCE, partial);
        vars.put(PipelineContextKeys.DEGRADATION_FEATURE_MANIFEST, manifest);
        vars.put(PipelineContextKeys.DEGRADATION_GAPS, gaps);
        vars.put(PipelineContextKeys.LAST_VALIDATED_NODE, mapper.createObjectNode());
        vars.put(PipelineContextKeys.LAST_VALIDATION_ERROR, "some transient validation error");
        when(extendedState.getVariables()).thenReturn(vars);

        action.execute(stateContext);

        MidasContext updated = (MidasContext) vars.get(PipelineContextKeys.MIDAS_CONTEXT);
        assertThat(updated.getGeneratedSourceCode()).isEqualTo(partial);
        assertThat(updated.getFeatureManifest()).isEqualTo(manifest);
        // Not an error — a delivered, degraded product.
        assertThat(updated.getLastErrorMessage()).isNull();
        assertThat(updated.getValidationRetries()).isZero();

        JsonNode report = updated.getCoverageReport();
        assertThat(report).isNotNull();
        assertThat(report.get("status").asText()).isEqualTo(MidasState.COMPLETED_WITH_GAPS.name());
        assertThat(report.get("build_verified").asBoolean()).isFalse();
        assertThat(report.get("delivered_file_count").asInt()).isEqualTo(2);
        assertThat(report.get("delivered_capabilities")).hasSize(2);
        assertThat(report.get("gaps")).hasSize(2);
        // coverage_matrix has one row per delivered capability + one per gap.
        assertThat(report.get("coverage_matrix")).hasSize(4);
        assertThat(report.get("summary").asText()).isNotBlank();

        // The DEGRADED_COMPLETION flag drives PipelineCompletionAction's terminal status selection.
        assertThat(vars.get(PipelineContextKeys.DEGRADED_COMPLETION)).isEqualTo(Boolean.TRUE);

        // Transient payload is consumed so a reused machine cannot leak it into a later run.
        assertThat(vars).doesNotContainKeys(
                PipelineContextKeys.DEGRADATION_PARTIAL_SOURCE,
                PipelineContextKeys.DEGRADATION_FEATURE_MANIFEST,
                PipelineContextKeys.DEGRADATION_GAPS,
                PipelineContextKeys.LAST_VALIDATED_NODE,
                PipelineContextKeys.LAST_VALIDATION_ERROR);

        // Honest degradation is recorded in the audit trail as a WARN, not an ERROR.
        assertThat(updated.safeAuditLog())
                .anyMatch(e -> e.getSeverity() == com.midas.d3.context.AuditEntry.Severity.WARN);
    }

    @Test
    @DisplayName("Missing MidasContext — action is a safe no-op, does not flag DEGRADED_COMPLETION")
    void execute_missingContext_isNoOp() {
        Map<Object, Object> vars = new HashMap<>();
        when(extendedState.getVariables()).thenReturn(vars);

        action.execute(stateContext);

        assertThat(vars).doesNotContainKey(PipelineContextKeys.DEGRADED_COMPLETION);
    }

    @Test
    @DisplayName("No feature manifest and no gaps — still delivers a valid report with a file count")
    void execute_noManifestNoGaps_stillProducesReport() throws Exception {
        MidasContext base = MidasContext.start("Build something", "run-degrade-002");
        JsonNode partial = mapper.readTree("{\"index.html\":\"<html></html>\"}");

        Map<Object, Object> vars = new HashMap<>();
        vars.put(PipelineContextKeys.MIDAS_CONTEXT, base);
        vars.put(PipelineContextKeys.DEGRADATION_PARTIAL_SOURCE, partial);
        when(extendedState.getVariables()).thenReturn(vars);

        action.execute(stateContext);

        MidasContext updated = (MidasContext) vars.get(PipelineContextKeys.MIDAS_CONTEXT);
        JsonNode report = updated.getCoverageReport();
        assertThat(report.get("delivered_file_count").asInt()).isEqualTo(1);
        assertThat(report.get("delivered_capabilities")).isEmpty();
        assertThat(report.get("gaps")).isEmpty();
        assertThat(vars.get(PipelineContextKeys.DEGRADED_COMPLETION)).isEqualTo(Boolean.TRUE);
    }
}

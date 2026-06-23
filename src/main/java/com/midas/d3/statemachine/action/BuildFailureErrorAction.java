package com.midas.d3.statemachine.action;

import com.fasterxml.jackson.databind.JsonNode;
import com.midas.d3.context.AuditEntry;
import com.midas.d3.context.MidasContext;
import com.midas.d3.statemachine.MidasEvent;
import com.midas.d3.statemachine.MidasState;
import com.midas.d3.statemachine.PipelineContextKeys;
import com.midas.d3.statemachine.PipelineTopology;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.statemachine.StateContext;
import org.springframework.statemachine.action.Action;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Fires on the {@code BUILD_CHOICE → ERROR} branch when the build keeps failing after the
 * remediation budget is exhausted. Records the last build report and a clear, build-specific
 * error message for post-mortem analysis (the generic {@link PipelineErrorAction} keys off a
 * validation error that does not exist here — the report validated fine, the build did not).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BuildFailureErrorAction implements Action<MidasState, MidasEvent> {

    private final PipelineTopology topology;

    @Override
    public void execute(StateContext<MidasState, MidasEvent> context) {
        Map<Object, Object> vars = context.getExtendedState().getVariables();

        MidasContext current = (MidasContext) vars.get(PipelineContextKeys.MIDAS_CONTEXT);
        JsonNode report = (JsonNode) vars.get(PipelineContextKeys.LAST_VALIDATED_NODE);

        if (current == null) {
            log.error("[BuildFailureErrorAction] MidasContext missing — cannot record build failure.");
            return;
        }

        String summary = report != null ? report.path("summary").asText("Build failed.") : "Build failed.";
        String message = "Build verification failed after " + topology.maxBuildRemediations()
                + " remediation attempt(s): " + summary;

        MidasContext failed = current
                .withLastErrorMessage(message)
                .appendAudit(AuditEntry.error(
                        MidasState.BUILD_VERIFICATION.name(),
                        "Pipeline aborted — build never compiled",
                        message));
        if (report != null) {
            failed = failed.withBuildReport(report);
        }

        vars.put(PipelineContextKeys.MIDAS_CONTEXT, failed);
        vars.remove(PipelineContextKeys.LAST_VALIDATED_NODE);
        vars.remove(PipelineContextKeys.LAST_VALIDATION_ERROR);

        log.error("[BuildFailureErrorAction] Run [{}] entered ERROR — {}",
                current.getPipelineRunId(), message);
    }
}

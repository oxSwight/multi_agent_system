package com.midas.d3.statemachine.guard;

import com.fasterxml.jackson.databind.JsonNode;
import com.midas.d3.context.MidasContext;
import com.midas.d3.statemachine.MidasEvent;
import com.midas.d3.statemachine.MidasState;
import com.midas.d3.statemachine.PipelineContextKeys;
import com.midas.d3.statemachine.PipelineTopology;
import com.midas.d3.validation.BuildVerificationValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.statemachine.StateContext;
import org.springframework.statemachine.guard.Guard;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * {@code first} branch of {@link MidasState#BUILD_CHOICE}: fires when the build report says
 * {@code FAILED} and the run still has build-remediation budget — routing back to
 * {@link MidasState#CODE_GENERATION} so the implementation agent can fix the compiler errors.
 *
 * <p>Mirror of {@link RemediationAvailableGuard} for the build gate. Returns {@code false} for a
 * {@code SUCCESS} report, which lets the choice fall through to {@link ValidationSucceededGuard}
 * and advance to the SecOps audit.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BuildRemediationAvailableGuard implements Guard<MidasState, MidasEvent> {

    private final PipelineTopology topology;

    @Override
    public boolean evaluate(StateContext<MidasState, MidasEvent> context) {
        if (!buildFailed(context)) {
            return false;
        }
        MidasContext ctx = extractContext(context);
        if (ctx == null) {
            return false;
        }
        boolean available = topology.isBuildRemediationAvailable(ctx);
        log.debug("[BuildRemediationAvailableGuard] attempts={} available={}",
                ctx.getBuildRemediationAttempts(), available);
        return available;
    }

    /** {@code true} iff the pending stage is BUILD_VERIFICATION and the validated report is FAILED. */
    static boolean buildFailed(StateContext<MidasState, MidasEvent> context) {
        Object pending = context.getExtendedState().getVariables().get(PipelineContextKeys.PENDING_STAGE);
        if (pending != MidasState.BUILD_VERIFICATION) {
            return false;
        }
        Object node = context.getExtendedState().getVariables().get(PipelineContextKeys.LAST_VALIDATED_NODE);
        if (!(node instanceof JsonNode report)) {
            return false;
        }
        String status = report.path("build_status").asText("").strip().toUpperCase(Locale.ROOT);
        return BuildVerificationValidator.STATUS_FAILED.equals(status);
    }

    static MidasContext extractContext(StateContext<MidasState, MidasEvent> context) {
        Object raw = context.getExtendedState().getVariables().get(PipelineContextKeys.MIDAS_CONTEXT);
        return (raw instanceof MidasContext ctx) ? ctx : null;
    }
}

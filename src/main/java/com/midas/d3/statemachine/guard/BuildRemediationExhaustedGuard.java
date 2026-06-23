package com.midas.d3.statemachine.guard;

import com.midas.d3.context.MidasContext;
import com.midas.d3.statemachine.MidasEvent;
import com.midas.d3.statemachine.MidasState;
import com.midas.d3.statemachine.PipelineTopology;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.statemachine.StateContext;
import org.springframework.statemachine.guard.Guard;
import org.springframework.stereotype.Component;

/**
 * {@code then} branch of {@link MidasState#BUILD_CHOICE}: fires when the build report says
 * {@code FAILED} and the build-remediation budget is exhausted — terminating the run in
 * {@link MidasState#ERROR} rather than looping forever.
 *
 * <p>Mirror of {@link RemediationExhaustedGuard} for the build gate.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BuildRemediationExhaustedGuard implements Guard<MidasState, MidasEvent> {

    private final PipelineTopology topology;

    @Override
    public boolean evaluate(StateContext<MidasState, MidasEvent> context) {
        if (!BuildRemediationAvailableGuard.buildFailed(context)) {
            return false;
        }
        MidasContext ctx = BuildRemediationAvailableGuard.extractContext(context);
        if (ctx == null) {
            return false;
        }
        boolean exhausted = topology.isBuildRemediationExhausted(ctx);
        log.debug("[BuildRemediationExhaustedGuard] attempts={} exhausted={}",
                ctx.getBuildRemediationAttempts(), exhausted);
        return exhausted;
    }
}

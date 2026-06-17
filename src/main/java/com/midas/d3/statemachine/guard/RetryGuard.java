package com.midas.d3.statemachine.guard;

import com.midas.d3.context.MidasContext;
import com.midas.d3.statemachine.MidasEvent;
import com.midas.d3.statemachine.MidasState;
import com.midas.d3.statemachine.PipelineContextKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.statemachine.StateContext;
import org.springframework.statemachine.guard.Guard;
import org.springframework.stereotype.Component;

/**
 * Guard for the explicit self-transition (retry stay) on {@code SUBMIT_RESULT}.
 *
 * <p>Returns {@code true} when validation has failed (otherwise
 * {@link ValidationPassedGuard} would have already fired and its transition taken)
 * and there is at least one retry remaining.
 *
 * <p><b>Why a dedicated guard instead of a no-guard fallback?</b>
 * In Spring State Machine 4.x, the priority ordering between external and internal
 * transitions is not strictly guaranteed when the internal transition has no guard
 * (always-true). Replacing the no-guard internal transition with an explicit
 * <i>external self-transition</i> (source == target) that carries this guard
 * ensures all three {@code SUBMIT_RESULT} transitions are external and thus evaluated
 * strictly in definition order.
 *
 * <p><b>Complement relationship:</b>
 * {@code RetryGuard} and {@link RetriesExhaustedGuard} are mutual complements over
 * the "retries + 1" check:
 * <ul>
 *   <li>RetryGuard:           {@code retries + 1 < maxRetries} → can still retry</li>
 *   <li>RetriesExhaustedGuard:{@code retries + 1 >= maxRetries} → must abort</li>
 * </ul>
 */
@Slf4j
@Component
public class RetryGuard implements Guard<MidasState, MidasEvent> {

    @Value("${midas.validation.max-retries:3}")
    private int maxRetries;

    @Override
    public boolean evaluate(StateContext<MidasState, MidasEvent> context) {
        MidasContext midasContext = extractContext(context);
        if (midasContext == null) {
            log.warn("[RetryGuard] MidasContext not found — returning false (no retry).");
            return false;
        }

        boolean canRetry = midasContext.getValidationRetries() + 1 < maxRetries;
        log.debug("[RetryGuard] retries={}/{} canRetry={}",
                midasContext.getValidationRetries(), maxRetries, canRetry);
        return canRetry;
    }

    private MidasContext extractContext(StateContext<MidasState, MidasEvent> context) {
        Object raw = context.getExtendedState().getVariables().get(PipelineContextKeys.MIDAS_CONTEXT);
        return (raw instanceof MidasContext ctx) ? ctx : null;
    }
}

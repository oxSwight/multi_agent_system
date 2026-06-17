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
 * Guard that returns {@code true} when the pipeline has exhausted all allowed
 * validation retries for the current stage.
 *
 * <p><b>Priority:</b> Evaluated SECOND among the three competing {@code SUBMIT_RESULT}
 * transitions. Only reached when {@link ValidationPassedGuard} returned {@code false}
 * (i.e., validation failed). When this guard returns {@code true}, the machine
 * transitions to {@link MidasState#ERROR}.
 *
 * <p>The retry counter lives in the immutable {@link MidasContext} stored in
 * {@code ExtendedState}. Incrementing it is the responsibility of
 * {@link com.midas.d3.statemachine.action.IncrementRetryAction}.
 */
@Slf4j
@Component
public class RetriesExhaustedGuard implements Guard<MidasState, MidasEvent> {

    @Value("${midas.validation.max-retries:3}")
    private int maxRetries;

    @Override
    public boolean evaluate(StateContext<MidasState, MidasEvent> context) {
        MidasContext midasContext = extractContext(context);
        if (midasContext == null) {
            log.warn("[RetriesExhaustedGuard] MidasContext not found in ExtendedState — returning false.");
            return false;
        }

        // Check "retries + 1 >= maxRetries" because the IncrementRetryAction hasn't run yet;
        // we're deciding whether the CURRENT failure (about to be counted) exhausts the budget.
        boolean exhausted = midasContext.getValidationRetries() + 1 >= maxRetries;
        log.debug("[RetriesExhaustedGuard] retries={}/{} exhausted={}",
                midasContext.getValidationRetries(), maxRetries, exhausted);
        return exhausted;
    }

    private MidasContext extractContext(StateContext<MidasState, MidasEvent> context) {
        Object raw = context.getExtendedState().getVariables().get(PipelineContextKeys.MIDAS_CONTEXT);
        return (raw instanceof MidasContext ctx) ? ctx : null;
    }
}

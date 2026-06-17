package com.midas.d3.statemachine.guard;

import com.fasterxml.jackson.databind.JsonNode;
import com.midas.d3.statemachine.MidasEvent;
import com.midas.d3.statemachine.MidasState;
import com.midas.d3.statemachine.PipelineContextKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.statemachine.StateContext;
import org.springframework.statemachine.guard.Guard;
import org.springframework.stereotype.Component;

/**
 * CHOICE branch guard used as the {@code first} branch in each stage's CHOICE state.
 *
 * <p>Returns {@code true} if {@link com.midas.d3.statemachine.action.ValidateAndCaptureAction}
 * successfully stored a validated {@link JsonNode} under
 * {@link PipelineContextKeys#LAST_VALIDATED_NODE} in {@code ExtendedState}.
 *
 * <p>This guard is intentionally side-effect-free — it only reads ExtendedState.
 * All validation logic is performed by {@code ValidateAndCaptureAction} which runs
 * on the entry transition into the CHOICE pseudo-state, before any branch guards
 * are evaluated.
 */
@Slf4j
@Component
public class ValidationSucceededGuard implements Guard<MidasState, MidasEvent> {

    @Override
    public boolean evaluate(StateContext<MidasState, MidasEvent> context) {
        Object node = context.getExtendedState().getVariables()
                .get(PipelineContextKeys.LAST_VALIDATED_NODE);
        boolean succeeded = node instanceof JsonNode;
        log.debug("[ValidationSucceededGuard] validationSucceeded={}", succeeded);
        return succeeded;
    }
}

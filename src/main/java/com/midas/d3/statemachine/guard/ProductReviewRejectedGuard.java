package com.midas.d3.statemachine.guard;

import com.fasterxml.jackson.databind.JsonNode;
import com.midas.d3.statemachine.MidasState;
import com.midas.d3.statemachine.MidasEvent;
import com.midas.d3.statemachine.PipelineContextKeys;
import com.midas.d3.validation.ControllerValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.statemachine.StateContext;
import org.springframework.statemachine.guard.Guard;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * CHOICE branch guard for the {@link MidasState#PRODUCT_REVIEW} quality gate.
 *
 * <p>Returns {@code true} only when the Controller's output passed schema validation
 * (a {@link JsonNode} is present under {@link PipelineContextKeys#LAST_VALIDATED_NODE}) AND
 * the {@code verdict} is {@code REJECT}. This is the FIRST branch of the PRODUCT_CHOICE state,
 * so a rejecting (but well-formed) verdict routes to {@link MidasState#ERROR} instead of
 * advancing to COMPLETED.
 *
 * <p>The guard is intentionally scoped to the PRODUCT_REVIEW pending stage so it is a strict
 * no-op for every other stage's CHOICE state — ordinary artifacts never carry a REJECT verdict.
 * It is side-effect-free: it only reads ExtendedState.
 */
@Slf4j
@Component
public class ProductReviewRejectedGuard implements Guard<MidasState, MidasEvent> {

    @Override
    public boolean evaluate(StateContext<MidasState, MidasEvent> context) {
        Object pending = context.getExtendedState().getVariables()
                .get(PipelineContextKeys.PENDING_STAGE);
        if (pending != MidasState.PRODUCT_REVIEW) {
            return false;
        }

        Object node = context.getExtendedState().getVariables()
                .get(PipelineContextKeys.LAST_VALIDATED_NODE);
        if (!(node instanceof JsonNode report)) {
            // Validation failed — let the standard retry/exhaustion branches handle it.
            return false;
        }

        String verdict = report.path("verdict").asText("").strip().toUpperCase(Locale.ROOT);
        boolean rejected = ControllerValidator.VERDICT_REJECT.equals(verdict);
        log.debug("[ProductReviewRejectedGuard] verdict={} rejected={}", verdict, rejected);
        return rejected;
    }
}

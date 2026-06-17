package com.midas.d3.statemachine.guard;

import com.fasterxml.jackson.databind.JsonNode;
import com.midas.d3.context.MidasContext;
import com.midas.d3.statemachine.MidasEvent;
import com.midas.d3.statemachine.MidasState;
import com.midas.d3.statemachine.PipelineContextKeys;
import com.midas.d3.statemachine.PipelineTopology;
import com.midas.d3.validation.ControllerValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.statemachine.StateContext;
import org.springframework.statemachine.guard.Guard;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Slf4j
@Component
@RequiredArgsConstructor
public class RemediationExhaustedGuard implements Guard<MidasState, MidasEvent> {

    private final PipelineTopology topology;

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
            return false;
        }

        String verdict = report.path("verdict").asText("").strip().toUpperCase(Locale.ROOT);
        if (!ControllerValidator.VERDICT_REJECT.equals(verdict)) {
            return false;
        }

        MidasContext midasContext = extractContext(context);
        if (midasContext == null) {
            return false;
        }

        boolean exhausted = topology.isProductReviewRemediationExhausted(midasContext);
        log.debug("[RemediationExhaustedGuard] attempts={} exhausted={}",
                midasContext.getProductReviewRemediationAttempts(), exhausted);
        return exhausted;
    }

    private MidasContext extractContext(StateContext<MidasState, MidasEvent> context) {
        Object raw = context.getExtendedState().getVariables().get(PipelineContextKeys.MIDAS_CONTEXT);
        return (raw instanceof MidasContext ctx) ? ctx : null;
    }
}

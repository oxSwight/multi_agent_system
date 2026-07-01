package com.midas.d3.statemachine.guard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.midas.d3.context.MidasContext;
import com.midas.d3.quality.QualityBackstop;
import com.midas.d3.quality.QualityBackstopProperties;
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

/**
 * Fires when a Controller {@code REJECT} that has exhausted its remediation budget — i.e. the run is
 * otherwise headed straight to ERROR — is contradicted by SUBSTANTIVE deterministic evidence
 * ({@link QualityBackstop}: a non-empty gated rubric fully satisfied + a build that did not fail).
 * It is placed BEFORE the {@link RemediationExhaustedGuard} {@code → ERROR} transition, so it
 * intercepts exactly that qualifying subset and lets the run COMPLETE (as {@code PASS_WITH_NOTES})
 * instead of failing on a flaky LLM verdict. Everything outside that subset still routes to ERROR.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QualityBackstopGuard implements Guard<MidasState, MidasEvent> {

    private final PipelineTopology topology;
    private final ObjectMapper objectMapper;
    private final QualityBackstopProperties properties;

    @Override
    public boolean evaluate(StateContext<MidasState, MidasEvent> context) {
        if (!properties.isEnabled()) {
            return false;
        }
        var vars = context.getExtendedState().getVariables();
        if (vars.get(PipelineContextKeys.PENDING_STAGE) != MidasState.PRODUCT_REVIEW) {
            return false;
        }
        if (!(vars.get(PipelineContextKeys.LAST_VALIDATED_NODE) instanceof JsonNode report)) {
            return false;
        }
        String verdict = report.path("verdict").asText("").strip().toUpperCase(Locale.ROOT);
        if (!ControllerValidator.VERDICT_REJECT.equals(verdict)) {
            return false;
        }
        if (!(vars.get(PipelineContextKeys.MIDAS_CONTEXT) instanceof MidasContext ctx)) {
            return false;
        }
        // Only when remediation is exhausted — otherwise the normal remediation path should run first.
        if (!topology.isProductReviewRemediationExhausted(ctx)) {
            return false;
        }
        boolean qualifies = QualityBackstop.qualifies(
                ctx.getTechnicalSpec(), ctx.getGeneratedSourceCode(),
                ctx.getGeneratedTests(), ctx.getBuildReport(), objectMapper);
        if (qualifies) {
            log.warn("[QualityBackstopGuard] run=[{}] Controller REJECT + remediation exhausted, but the "
                    + "gated rubric is fully satisfied and the build did not fail — backstopping to PASS_WITH_NOTES.",
                    ctx.getPipelineRunId());
        }
        return qualifies;
    }
}

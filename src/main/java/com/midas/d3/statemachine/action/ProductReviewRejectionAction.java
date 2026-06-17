package com.midas.d3.statemachine.action;

import com.fasterxml.jackson.databind.JsonNode;
import com.midas.d3.context.AuditEntry;
import com.midas.d3.context.MidasContext;
import com.midas.d3.statemachine.MidasEvent;
import com.midas.d3.statemachine.MidasState;
import com.midas.d3.statemachine.PipelineContextKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.statemachine.StateContext;
import org.springframework.statemachine.action.Action;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Transition action fired when the {@link MidasState#PRODUCT_REVIEW} quality gate returns a
 * {@code REJECT} verdict (PRODUCT_CHOICE → ERROR, guarded by
 * {@link com.midas.d3.statemachine.guard.ProductReviewRejectedGuard}).
 *
 * <p>Unlike a schema failure, a REJECT is a well-formed, intentional decision by the Controller.
 * This action therefore <b>attaches the full conformance report</b> to the immutable
 * {@link MidasContext} (so it is retrievable via the REST {@code /context} endpoint and packaged
 * with the run), records a human-readable rejection summary as the last error message, and appends
 * an ERROR audit entry — then the machine terminates in {@link MidasState#ERROR}.
 *
 * <p>The {@link MidasContext} is never mutated in place; a new instance is produced via the
 * {@code withXxx()} copy methods, preserving immutability.
 */
@Slf4j
@Component
public class ProductReviewRejectionAction implements Action<MidasState, MidasEvent> {

    @Override
    public void execute(StateContext<MidasState, MidasEvent> context) {
        Map<Object, Object> vars = context.getExtendedState().getVariables();

        MidasContext current = (MidasContext) vars.get(PipelineContextKeys.MIDAS_CONTEXT);
        if (current == null) {
            log.error("[ProductReviewRejectionAction] MidasContext missing — cannot record rejection.");
            return;
        }

        JsonNode report = (JsonNode) vars.get(PipelineContextKeys.LAST_VALIDATED_NODE);
        String errorMessage = buildRejectionMessage(report);

        MidasContext rejected = current
                .withProductReviewReport(report)
                .withLastErrorMessage(errorMessage)
                .appendAudit(AuditEntry.error(
                        MidasState.PRODUCT_REVIEW.name(),
                        "Product-Owner quality gate REJECTED the build — intent not satisfied",
                        errorMessage));

        vars.put(PipelineContextKeys.MIDAS_CONTEXT, rejected);
        vars.remove(PipelineContextKeys.LAST_VALIDATED_NODE);
        vars.remove(PipelineContextKeys.LAST_VALIDATION_ERROR);

        log.error("[ProductReviewRejectionAction] Run [{}] REJECTED at PRODUCT_REVIEW: {}",
                current.getPipelineRunId(), errorMessage);
    }

    /** Builds a concise, human-readable rejection summary from the verdict report. */
    private String buildRejectionMessage(JsonNode report) {
        if (report == null) {
            return "Product review REJECTED (no report captured).";
        }
        StringBuilder sb = new StringBuilder("Product review REJECTED");
        String summary = report.path("summary").asText("");
        if (!summary.isBlank()) {
            sb.append(": ").append(summary.strip());
        }
        JsonNode required = report.path("remediation_block").path("required_changes");
        if (required.isArray() && !required.isEmpty()) {
            sb.append(" | Required changes: ");
            for (int i = 0; i < required.size(); i++) {
                if (i > 0) sb.append("; ");
                sb.append(required.get(i).asText("").strip());
            }
        }
        return sb.toString();
    }
}

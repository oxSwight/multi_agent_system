package com.midas.d3.statemachine.action;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.midas.d3.context.AuditEntry;
import com.midas.d3.context.MidasContext;
import com.midas.d3.statemachine.AgentDispatcher;
import com.midas.d3.statemachine.MidasEvent;
import com.midas.d3.statemachine.MidasState;
import com.midas.d3.statemachine.PipelineContextKeys;
import com.midas.d3.statemachine.PipelineTopology;
import com.midas.d3.validation.ControllerValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.statemachine.StateContext;
import org.springframework.statemachine.action.Action;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class RemediationInitAction implements Action<MidasState, MidasEvent> {

    private final ObjectMapper objectMapper;
    private final PipelineTopology topology;
    private final AgentDispatcher agentDispatcher;

    @Override
    public void execute(StateContext<MidasState, MidasEvent> context) {
        Map<Object, Object> vars = context.getExtendedState().getVariables();

        MidasContext current = (MidasContext) vars.get(PipelineContextKeys.MIDAS_CONTEXT);
        JsonNode report = (JsonNode) vars.get(PipelineContextKeys.LAST_VALIDATED_NODE);

        if (current == null) {
            log.error("[RemediationInitAction] MidasContext missing — cannot initiate remediation.");
            return;
        }
        if (report == null) {
            log.error("[RemediationInitAction] LAST_VALIDATED_NODE missing — cannot initiate remediation.");
            return;
        }

        int nextAttempt = current.getProductReviewRemediationAttempts() + 1;
        JsonNode directive = buildRemediationDirective(report, nextAttempt);

        MidasContext remediated = current
                .withProductReviewReport(report)
                .withGeneratedSourceCode(null)
                .withGeneratedTests(null)
                .withSecOpsArtifacts(null)
                .withRemediationDirective(directive)
                .withProductReviewRemediationAttempts(nextAttempt)
                .withValidationRetries(0)
                .appendAudit(AuditEntry.warn(
                        MidasState.PRODUCT_REVIEW.name(),
                        "Product review remediation initiated",
                        "Remediation loop " + nextAttempt + "/"
                                + topology.maxProductReviewRemediations()
                                + " → CODE_GENERATION"));

        vars.put(PipelineContextKeys.MIDAS_CONTEXT, remediated);
        vars.remove(PipelineContextKeys.LAST_VALIDATED_NODE);
        vars.remove(PipelineContextKeys.LAST_VALIDATION_ERROR);

        log.warn("[RemediationInitAction] Run [{}] remediation {}/{} initiated → CODE_GENERATION",
                current.getPipelineRunId(), nextAttempt, topology.maxProductReviewRemediations());

        agentDispatcher.dispatchIfAutoMode(context.getStateMachine(), MidasState.CODE_GENERATION);
    }

    private JsonNode buildRemediationDirective(JsonNode report, int remediationAttempt) {
        ObjectNode directive = objectMapper.createObjectNode();
        directive.put("source_verdict", ControllerValidator.VERDICT_REJECT);
        directive.put("summary", report.path("summary").asText("").strip());
        directive.set("required_changes",
                copyStringArray(report.path("remediation_block").path("required_changes")));
        directive.set("coverage_gaps", filterCoverageGaps(report.path("coverage_matrix")));
        directive.put("remediation_attempt", remediationAttempt);
        directive.put("max_remediation_attempts", topology.maxProductReviewRemediations());
        return directive;
    }

    private ArrayNode filterCoverageGaps(JsonNode coverageMatrix) {
        ArrayNode gaps = objectMapper.createArrayNode();
        if (!coverageMatrix.isArray()) {
            return gaps;
        }
        for (JsonNode row : coverageMatrix) {
            String status = row.path("status").asText("").strip().toUpperCase(Locale.ROOT);
            if ("MISSING".equals(status) || "PARTIAL".equals(status)) {
                gaps.add(row.deepCopy());
            }
        }
        return gaps;
    }

    private ArrayNode copyStringArray(JsonNode arrayNode) {
        ArrayNode copy = objectMapper.createArrayNode();
        if (arrayNode.isArray()) {
            for (JsonNode item : arrayNode) {
                copy.add(item.asText("").strip());
            }
        }
        return copy;
    }
}

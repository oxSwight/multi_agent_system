package com.midas.d3.statemachine.action;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.midas.d3.context.MidasContext;
import com.midas.d3.statemachine.MidasEvent;
import com.midas.d3.statemachine.MidasState;
import com.midas.d3.statemachine.PipelineContextKeys;
import com.midas.d3.validation.ControllerValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.statemachine.StateContext;
import org.springframework.statemachine.action.Action;
import org.springframework.stereotype.Component;

/**
 * Applies the quality backstop: rewrites the Controller's {@code REJECT} verdict to
 * {@code PASS_WITH_NOTES} (retaining the original verdict's findings as advisory) and then delegates
 * to {@link StoreArtifactAction} to store the report and advance the pipeline to COMPLETED. Reached
 * only when {@link com.midas.d3.statemachine.guard.QualityBackstopGuard} has already confirmed
 * substantive deterministic evidence, so this action does not re-judge — it records the override.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QualityBackstopAction implements Action<MidasState, MidasEvent> {

    private final StoreArtifactAction storeArtifactAction;

    @Override
    public void execute(StateContext<MidasState, MidasEvent> context) {
        var vars = context.getExtendedState().getVariables();
        Object node = vars.get(PipelineContextKeys.LAST_VALIDATED_NODE);
        if (node instanceof JsonNode report && report.isObject()) {
            ObjectNode rewritten = ((ObjectNode) report).deepCopy();
            String original = rewritten.path("summary").asText("");
            rewritten.put("verdict", ControllerValidator.VERDICT_PASS_WITH_NOTES);
            rewritten.put("summary",
                    "Auto-approved by the deterministic quality backstop: every gated acceptance "
                            + "criterion is satisfied and the build did not fail; the Controller's REJECT is "
                            + "retained below as advisory. Original summary: " + original);
            vars.put(PipelineContextKeys.LAST_VALIDATED_NODE, rewritten);

            String runId = vars.get(PipelineContextKeys.MIDAS_CONTEXT) instanceof MidasContext ctx
                    ? ctx.getPipelineRunId() : "?";
            log.warn("[QualityBackstopAction] run=[{}] Controller REJECT overridden → PASS_WITH_NOTES "
                    + "(deterministic gated rubric fully satisfied; build did not fail).", runId);
        }
        // Delegate storage + completion routing to the canonical action (single source of truth).
        storeArtifactAction.execute(context);
    }
}

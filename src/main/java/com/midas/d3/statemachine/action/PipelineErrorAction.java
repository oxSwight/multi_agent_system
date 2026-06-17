package com.midas.d3.statemachine.action;

import com.midas.d3.context.AuditEntry;
import com.midas.d3.context.MidasContext;
import com.midas.d3.statemachine.MidasEvent;
import com.midas.d3.statemachine.MidasState;
import com.midas.d3.statemachine.PipelineContextKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.statemachine.StateContext;
import org.springframework.statemachine.action.Action;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Action that fires when the pipeline transitions to {@link MidasState#ERROR}
 * after exhausting all validation retries.
 *
 * <p>Records the final error details in {@link MidasContext} for post-mortem
 * analysis and clears the transient validation keys from {@code ExtendedState}.
 */
@Slf4j
@Component
public class PipelineErrorAction implements Action<MidasState, MidasEvent> {

    @Value("${midas.validation.max-retries:3}")
    private int maxRetries;

    @Override
    public void execute(StateContext<MidasState, MidasEvent> context) {
        Map<Object, Object> vars = context.getExtendedState().getVariables();

        MidasContext current = (MidasContext) vars.get(PipelineContextKeys.MIDAS_CONTEXT);
        String lastError     = (String) vars.getOrDefault(
                PipelineContextKeys.LAST_VALIDATION_ERROR, "Validation retries exhausted");
        String stage         = resolveSourceStage(context);

        if (current == null) {
            log.error("[PipelineErrorAction] MidasContext missing — cannot record error state.");
            return;
        }

        MidasContext failed = current
                .withLastErrorMessage(lastError)
                .appendAudit(AuditEntry.error(stage,
                        "Pipeline aborted after %d failed retries".formatted(maxRetries),
                        lastError));

        vars.put(PipelineContextKeys.MIDAS_CONTEXT, failed);
        vars.remove(PipelineContextKeys.LAST_VALIDATED_NODE);
        vars.remove(PipelineContextKeys.LAST_VALIDATION_ERROR);

        log.error("[PipelineErrorAction] Pipeline run [{}] entered ERROR at stage [{}] — {}",
                current.getPipelineRunId(), stage, lastError);
    }

    private String resolveSourceStage(StateContext<MidasState, MidasEvent> context) {
        Object pending = context.getExtendedState().getVariables().get(PipelineContextKeys.PENDING_STAGE);
        if (pending instanceof MidasState s) return s.name();
        try {
            return context.getSource() != null
                    ? context.getSource().getId().name()
                    : "UNKNOWN";
        } catch (Exception e) {
            return "UNKNOWN";
        }
    }
}

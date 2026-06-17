package com.midas.d3.statemachine.action;

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
 * Action triggered by a {@link MidasEvent#CRITICAL_FAILURE} event.
 *
 * <p>This fires when a {@link com.midas.d3.agent.base.BaseMidasAgent} exhausts all its
 * internal retries and throws {@link com.midas.d3.agent.base.AgentExecutionException}.
 * Unlike the {@link PipelineErrorAction} (which is invoked via the CHOICE pseudo-state
 * after the LLM output has been validated), this action takes effect immediately and
 * bypasses the normal validation-retry loop.
 *
 * <p>Responsibilities:
 * <ol>
 *   <li>Records the agent failure message in {@link MidasContext#withLastErrorMessage(String)}.</li>
 *   <li>Appends an ERROR audit entry.</li>
 *   <li>Clears transient ExtendedState keys.</li>
 * </ol>
 */
@Slf4j
@Component
public class CriticalFailureAction implements Action<MidasState, MidasEvent> {

    @Override
    public void execute(StateContext<MidasState, MidasEvent> context) {
        Map<Object, Object> vars = context.getExtendedState().getVariables();

        MidasContext current = (MidasContext) vars.get(PipelineContextKeys.MIDAS_CONTEXT);
        if (current == null) {
            log.error("[CriticalFailureAction] MidasContext missing — cannot record failure.");
            return;
        }

        String errorMsg = resolveErrorMessage(context);
        String stage    = resolveSourceStage(context);

        MidasContext failed = current
                .withLastErrorMessage(errorMsg)
                .appendAudit(AuditEntry.error(
                        stage,
                        "Agent exhausted all retries — pipeline aborted",
                        errorMsg));

        vars.put(PipelineContextKeys.MIDAS_CONTEXT, failed);
        vars.remove(PipelineContextKeys.LAST_VALIDATED_NODE);
        vars.remove(PipelineContextKeys.LAST_VALIDATION_ERROR);

        log.error("[CriticalFailureAction] Run [{}] → CRITICAL FAILURE at stage [{}]: {}",
                current.getPipelineRunId(), stage, errorMsg);
    }

    private String resolveErrorMessage(StateContext<MidasState, MidasEvent> context) {
        Object raw = context.getMessageHeader(PipelineContextKeys.AGENT_ERROR_HEADER);
        if (raw instanceof String s && !s.isBlank()) return s;
        return "Agent exhausted all retries — no further details available";
    }

    private String resolveSourceStage(StateContext<MidasState, MidasEvent> context) {
        try {
            if (context.getSource() != null && context.getSource().getId() != null) {
                return context.getSource().getId().name();
            }
        } catch (Exception ignored) {}
        return "UNKNOWN";
    }
}

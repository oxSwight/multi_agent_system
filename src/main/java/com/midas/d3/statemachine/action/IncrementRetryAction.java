package com.midas.d3.statemachine.action;

import com.midas.d3.context.AuditEntry;
import com.midas.d3.context.MidasContext;
import com.midas.d3.statemachine.AgentDispatcher;
import com.midas.d3.statemachine.MidasEvent;
import com.midas.d3.statemachine.MidasState;
import com.midas.d3.statemachine.PipelineContextKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.statemachine.StateContext;
import org.springframework.statemachine.action.Action;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Internal-transition action that fires when validation has failed but retries are
 * not yet exhausted (the fallback third transition on {@code SUBMIT_RESULT}).
 *
 * <p>Increments the {@link MidasContext#getValidationRetries()} counter and appends
 * a {@link AuditEntry.Severity#WARN} audit entry. The machine stays in the current
 * state — the caller is expected to re-invoke the LLM and submit a new result.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IncrementRetryAction implements Action<MidasState, MidasEvent> {

    private final AgentDispatcher agentDispatcher;

    @Value("${midas.validation.max-retries:3}")
    private int maxRetries;

    @Override
    public void execute(StateContext<MidasState, MidasEvent> context) {
        Map<Object, Object> vars = context.getExtendedState().getVariables();
        MidasContext current = (MidasContext) vars.get(PipelineContextKeys.MIDAS_CONTEXT);

        if (current == null) {
            log.error("[IncrementRetryAction] MidasContext not found — cannot increment retry counter.");
            return;
        }

        int newRetryCount = current.getValidationRetries() + 1;
        String lastError  = (String) vars.getOrDefault(PipelineContextKeys.LAST_VALIDATION_ERROR,
                "Unknown validation error");
        String stage      = resolveStage(context);

        MidasContext updated = current
                .withValidationRetries(newRetryCount)
                .appendAudit(AuditEntry.warn(stage,
                        "Validation failed — retry %d/%d".formatted(newRetryCount, maxRetries),
                        lastError));

        vars.put(PipelineContextKeys.MIDAS_CONTEXT, updated);

        log.warn("[IncrementRetryAction][{}] Retry {}/{} — cause: {}",
                stage, newRetryCount, maxRetries, lastError);

        // CHOICE → same-stage retry transitions do not trigger stateEntry actions in SSM.
        MidasState retryStage = resolvePendingStage(context);
        agentDispatcher.dispatchIfAutoMode(context.getStateMachine(), retryStage);
    }

    private MidasState resolvePendingStage(StateContext<MidasState, MidasEvent> context) {
        Object pending = context.getExtendedState().getVariables().get(PipelineContextKeys.PENDING_STAGE);
        return (pending instanceof MidasState s) ? s : null;
    }

    private String resolveStage(StateContext<MidasState, MidasEvent> context) {
        Object pending = context.getExtendedState().getVariables().get(PipelineContextKeys.PENDING_STAGE);
        if (pending instanceof MidasState s) return s.name();
        try {
            MidasState state = context.getStateMachine().getState().getId();
            return state != null ? state.name() : "UNKNOWN";
        } catch (Exception e) {
            return "UNKNOWN";
        }
    }
}

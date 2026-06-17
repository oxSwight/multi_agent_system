package com.midas.d3.statemachine.action;

import com.midas.d3.statemachine.AgentDispatcher;
import com.midas.d3.statemachine.MidasEvent;
import com.midas.d3.statemachine.MidasState;
import lombok.RequiredArgsConstructor;
import org.springframework.statemachine.StateContext;
import org.springframework.statemachine.action.Action;
import org.springframework.stereotype.Component;

/**
 * State-entry hook that delegates agent dispatch to {@link AgentDispatcher}.
 *
 * <p>Covers direct transitions (e.g. {@code IDLE → SYSTEM_ANALYSIS},
 * {@code WAITING_FOR_USER_INPUT → SYSTEM_ANALYSIS}). Choice-state transitions
 * are handled separately by {@link StoreArtifactAction} and
 * {@link IncrementRetryAction}.
 */
@Component
@RequiredArgsConstructor
public class AgentEntryAction implements Action<MidasState, MidasEvent> {

    private final AgentDispatcher agentDispatcher;

    @Override
    public void execute(StateContext<MidasState, MidasEvent> ctx) {
        MidasState target = ctx.getTarget() != null ? ctx.getTarget().getId() : null;
        if (target == null) return;
        agentDispatcher.dispatchIfAutoMode(ctx.getStateMachine(), target);
    }
}

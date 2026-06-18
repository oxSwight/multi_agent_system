package com.midas.d3.statemachine;

import com.midas.d3.agent.base.AgentExecutionException;
import com.midas.d3.agent.base.AgentResult;
import com.midas.d3.agent.base.BaseMidasAgent;
import com.midas.d3.config.AsyncConfig;
import com.midas.d3.context.MidasContext;
import com.midas.d3.llm.LlmCallException;
import com.midas.d3.persistence.PersistenceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.statemachine.StateMachine;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Dispatches {@link BaseMidasAgent} tasks asynchronously in auto-drive mode.
 *
 * <p>Spring State Machine does not invoke {@code stateEntry} actions when a processing
 * state is entered via a CHOICE pseudo-state transition (the success and retry paths).
 * Callers must therefore invoke {@link #dispatchIfAutoMode} explicitly from
 * {@link com.midas.d3.statemachine.action.StoreArtifactAction} and
 * {@link com.midas.d3.statemachine.action.IncrementRetryAction} in addition to the
 * {@link com.midas.d3.statemachine.action.AgentEntryAction} state-entry hook.
 */
@Slf4j
@Component
public class AgentDispatcher {

    /** Mandatory pause between agent invocations to stay under Gemini free-tier RPM limits. */
    private static final long INTER_AGENT_DELAY_MS = 10_000L;

    private final Map<MidasState, BaseMidasAgent> agentMap;
    private final Executor agentTaskExecutor;
    private final PersistenceService persistenceService;

    public AgentDispatcher(List<BaseMidasAgent> agents,
                           @Qualifier(AsyncConfig.AGENT_EXECUTOR) Executor agentTaskExecutor,
                           PersistenceService persistenceService) {
        this.agentTaskExecutor  = agentTaskExecutor;
        this.persistenceService = persistenceService;
        this.agentMap = new EnumMap<>(MidasState.class);
        for (BaseMidasAgent agent : agents) {
            MidasState state = agent.resolveStage();
            agentMap.put(state, agent);
            log.debug("[AgentDispatcher] Registered agent [{}] → state [{}]",
                    agent.getAgentName(), state);
        }
    }

    /**
     * Dispatches the agent for {@code state} when {@link PipelineContextKeys#AUTO_MODE_KEY}
     * is {@code true} in the machine's extended state.
     */
    public void dispatchIfAutoMode(StateMachine<MidasState, MidasEvent> machine, MidasState state) {
        if (state == null) return;

        Boolean autoMode = (Boolean) machine.getExtendedState()
                .getVariables().get(PipelineContextKeys.AUTO_MODE_KEY);
        if (!Boolean.TRUE.equals(autoMode)) {
            log.debug("[AgentDispatcher] Auto-mode disabled for state [{}] — skipping.", state);
            return;
        }

        dispatch(machine, state);
    }

    /** Unconditionally dispatches the agent mapped to {@code state}. */
    public void dispatch(StateMachine<MidasState, MidasEvent> machine, MidasState state) {
        BaseMidasAgent agent = agentMap.get(state);
        if (agent == null) {
            log.debug("[AgentDispatcher] No agent registered for state [{}].", state);
            return;
        }

        MidasContext context = (MidasContext) machine.getExtendedState()
                .getVariables().get(PipelineContextKeys.MIDAS_CONTEXT);
        if (context == null) {
            log.error("[AgentDispatcher] MidasContext is null at state [{}] — cannot dispatch.", state);
            return;
        }

        String runId = context.getPipelineRunId();
        persistenceService.updateRunStatus(runId, state.name());

        log.info("[AgentDispatcher] Dispatching [{}] async for run [{}].", agent.getAgentName(), runId);

        CompletableFuture.runAsync(
                () -> runAgent(agent, context, runId, machine, state),
                agentTaskExecutor);
    }

    private void runAgent(BaseMidasAgent agent,
                          MidasContext context,
                          String runId,
                          StateMachine<MidasState, MidasEvent> machine,
                          MidasState currentState) {
        long startMs = System.currentTimeMillis();

        try {
            log.debug("[AgentDispatcher] Throttling {} ms before agent [{}] for run [{}].",
                    INTER_AGENT_DELAY_MS, agent.getAgentName(), runId);
            Thread.sleep(INTER_AGENT_DELAY_MS);

            AgentResult result = agent.execute(context);
            long elapsedMs = System.currentTimeMillis() - startMs;

            if (result.isNeedsInfo()) {
                log.info("[AgentDispatcher] Agent [{}] needs user clarification for run [{}] ({}ms).",
                        agent.getAgentName(), runId, elapsedMs);

                persistenceService.logAgentExecution(
                        runId, agent.getAgentName(), result.rawLlmOutput(),
                        result.modelId(), result.promptTokens(), result.completionTokens(), elapsedMs, false);

                machine.getExtendedState().getVariables()
                       .put(PipelineContextKeys.ANALYST_QUESTIONS_KEY, result.rawLlmOutput());

                sendEvent(machine, MessageBuilder
                        .withPayload(MidasEvent.ANALYST_NEEDS_INFO)
                        .build());
                return;
            }

            log.info("[AgentDispatcher] Agent [{}] succeeded for run [{}] in {} attempt(s), {}ms.",
                    agent.getAgentName(), runId, result.attemptsUsed(), elapsedMs);

            persistenceService.logAgentExecution(
                    runId, agent.getAgentName(), result.rawLlmOutput(),
                    result.modelId(), result.promptTokens(), result.completionTokens(), elapsedMs, false);

            sendEvent(machine, MessageBuilder
                    .withPayload(MidasEvent.SUBMIT_RESULT)
                    .setHeader(PipelineContextKeys.LLM_OUTPUT_HEADER, result.rawLlmOutput())
                    .build());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            long elapsedMs = System.currentTimeMillis() - startMs;
            log.error("[AgentDispatcher] Interrupted during throttle delay for agent [{}] run [{}].",
                    agent.getAgentName(), runId);
            persistenceService.logAgentExecution(
                    runId, agent.getAgentName(), "Interrupted during inter-agent throttle",
                    null, 0, 0, elapsedMs, true);
            sendCriticalFailure(machine, "Agent dispatch interrupted");

        } catch (AgentExecutionException e) {
            long elapsedMs = System.currentTimeMillis() - startMs;
            log.error("[AgentDispatcher] Agent [{}] exhausted retries for run [{}] ({}ms): {}",
                    agent.getAgentName(), runId, elapsedMs, e.getMessage());

            persistenceService.logAgentExecution(
                    runId, agent.getAgentName(), e.getMessage(),
                    null, 0, 0, elapsedMs, true);

            sendCriticalFailure(machine, e.getMessage());

        } catch (LlmCallException e) {
            long elapsedMs = System.currentTimeMillis() - startMs;
            log.error("[AgentDispatcher] LLM call failed for agent [{}] run [{}] ({}ms): {}",
                    agent.getAgentName(), runId, elapsedMs, e.getMessage());

            persistenceService.logAgentExecution(
                    runId, agent.getAgentName(), e.getMessage(),
                    null, 0, 0, elapsedMs, true);

            sendCriticalFailure(machine, e.getMessage());

        } catch (Exception e) {
            long elapsedMs = System.currentTimeMillis() - startMs;
            log.error("[AgentDispatcher] Unexpected error in agent [{}] for run [{}] ({}ms): {}",
                    agent.getAgentName(), runId, elapsedMs, e.getMessage(), e);

            persistenceService.logAgentExecution(
                    runId, agent.getAgentName(), "Unexpected error: " + e.getMessage(),
                    null, 0, 0, elapsedMs, true);

            sendCriticalFailure(machine, "Unexpected agent error: " + e.getMessage());
        }
    }

    private void sendEvent(StateMachine<MidasState, MidasEvent> machine,
                           org.springframework.messaging.Message<MidasEvent> msg) {
        try {
            machine.sendEvent(Mono.just(msg)).blockLast();
        } catch (Exception e) {
            log.error("[AgentDispatcher] Failed to send event [{}] to machine: {}",
                    msg.getPayload(), e.getMessage(), e);
        }
    }

    private void sendCriticalFailure(StateMachine<MidasState, MidasEvent> machine, String errorMessage) {
        sendEvent(machine, MessageBuilder
                .withPayload(MidasEvent.CRITICAL_FAILURE)
                .setHeader(PipelineContextKeys.AGENT_ERROR_HEADER,
                        errorMessage != null ? errorMessage : "Agent exhausted all retries")
                .build());
    }
}

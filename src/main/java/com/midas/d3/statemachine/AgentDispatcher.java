package com.midas.d3.statemachine;

import com.midas.d3.agent.base.AgentExecutionException;
import com.midas.d3.agent.base.AgentResult;
import com.midas.d3.agent.base.BaseMidasAgent;
import com.midas.d3.agent.implementation.CodeGapDegradationException;
import com.midas.d3.build.BuildVerificationService;
import com.midas.d3.config.AsyncConfig;
import com.midas.d3.context.MidasContext;
import com.midas.d3.llm.LlmCallException;
import com.midas.d3.persistence.PersistenceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
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
 *
 * <p><b>Deterministic (non-LLM) stages.</b> Not every pipeline stage is an
 * {@link BaseMidasAgent}: {@link MidasState#BUILD_VERIFICATION} is a real sandboxed build, not an
 * LLM call. In the synchronous REST path {@code AgentOrchestrationService} runs it inline, but in
 * auto-drive mode the dispatcher is the only thing entering the stage — so it must run the build
 * itself and emit {@link MidasEvent#SUBMIT_RESULT} with the report, exactly as it does for an LLM
 * agent. Without this, an auto-mode (e.g. Telegram) run would reach {@code BUILD_VERIFICATION} and
 * wedge there forever, waiting for a result that nothing produces.
 */
@Slf4j
@Component
public class AgentDispatcher {

    /** Mandatory pause between agent invocations to stay under Gemini free-tier RPM limits. */
    private static final long INTER_AGENT_DELAY_MS = 10_000L;

    /** Pseudo-agent name under which the deterministic build gate is logged. */
    private static final String BUILD_VERIFICATION_AGENT_NAME = "BuildVerification";

    private final Map<MidasState, BaseMidasAgent> agentMap;
    private final Executor agentTaskExecutor;
    private final PersistenceService persistenceService;
    private final BuildVerificationService buildVerificationService;

    /** When true (default), an unhealable CODE_GENERATION functional gap degrades to COMPLETED_WITH_GAPS
     *  instead of a client-visible CRITICAL FAILURE. Opt-out via {@code midas.degradation.enabled=false}. */
    private final boolean degradationEnabled;

    public AgentDispatcher(List<BaseMidasAgent> agents,
                           @Qualifier(AsyncConfig.AGENT_EXECUTOR) Executor agentTaskExecutor,
                           PersistenceService persistenceService,
                           BuildVerificationService buildVerificationService,
                           @Value("${midas.degradation.enabled:true}") boolean degradationEnabled) {
        this.agentTaskExecutor  = agentTaskExecutor;
        this.persistenceService = persistenceService;
        this.buildVerificationService = buildVerificationService;
        this.degradationEnabled = degradationEnabled;
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

    /** Unconditionally dispatches the work mapped to {@code state} — an LLM agent or a deterministic stage. */
    public void dispatch(StateMachine<MidasState, MidasEvent> machine, MidasState state) {
        BaseMidasAgent agent = agentMap.get(state);
        // A stage is drivable here if it has an LLM agent or is a deterministic (non-LLM) stage the
        // dispatcher runs itself. Anything else (terminal/CHOICE states) is a no-op, as before.
        if (agent == null && !isDeterministicStage(state)) {
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

        if (agent != null) {
            log.info("[AgentDispatcher] Dispatching [{}] async for run [{}].", agent.getAgentName(), runId);
            CompletableFuture.runAsync(
                    () -> runAgent(agent, context, runId, machine, state),
                    agentTaskExecutor);
        } else {
            log.info("[AgentDispatcher] Running deterministic stage [{}] async for run [{}].", state, runId);
            CompletableFuture.runAsync(
                    () -> runBuildVerification(context, runId, machine),
                    agentTaskExecutor);
        }
    }

    /** True for non-LLM stages the dispatcher executes itself rather than delegating to a {@link BaseMidasAgent}. */
    private static boolean isDeterministicStage(MidasState state) {
        return state == MidasState.BUILD_VERIFICATION;
    }

    /**
     * Runs the deterministic {@link MidasState#BUILD_VERIFICATION} gate off the state-machine thread
     * and feeds its report back as a {@link MidasEvent#SUBMIT_RESULT}, so the BUILD_CHOICE routing
     * (success → SecOps, failure → self-healing remediation) advances exactly as in the REST path.
     *
     * <p>No inter-agent throttle and no token accounting — this is local tooling, not an LLM call.
     * {@code BuildVerificationService} is itself fail-open (a missing toolchain yields a SUCCESS
     * "skipped" report rather than a hang), so the only way here is an unexpected error, which is
     * routed to {@code CRITICAL_FAILURE} rather than left to wedge the machine.
     */
    private void runBuildVerification(MidasContext context, String runId,
                                      StateMachine<MidasState, MidasEvent> machine) {
        long startMs = System.currentTimeMillis();
        try {
            String reportJson = buildVerificationService.verifyToReportJson(context);
            long elapsedMs = System.currentTimeMillis() - startMs;

            log.info("[AgentDispatcher] Build verification completed for run [{}] in {}ms.", runId, elapsedMs);
            persistenceService.logAgentExecution(
                    runId, BUILD_VERIFICATION_AGENT_NAME, reportJson, null, 0, 0, elapsedMs, false);

            sendEvent(machine, MessageBuilder
                    .withPayload(MidasEvent.SUBMIT_RESULT)
                    .setHeader(PipelineContextKeys.LLM_OUTPUT_HEADER, reportJson)
                    .build());

        } catch (Exception e) {
            long elapsedMs = System.currentTimeMillis() - startMs;
            log.error("[AgentDispatcher] Build verification failed unexpectedly for run [{}] ({}ms): {}",
                    runId, elapsedMs, e.getMessage(), e);
            persistenceService.logAgentExecution(
                    runId, BUILD_VERIFICATION_AGENT_NAME, "Build verification error: " + e.getMessage(),
                    null, 0, 0, elapsedMs, true);
            sendCriticalFailure(machine, "Build verification error: " + e.getMessage());
        }
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
                        result.modelId(), result.promptTokens(), result.completionTokens(),
                        elapsedMs, false, result.finishReason());

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
                    result.modelId(), result.promptTokens(), result.completionTokens(),
                    elapsedMs, false, result.finishReason());

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

        } catch (CodeGapDegradationException e) {
            long elapsedMs = System.currentTimeMillis() - startMs;
            if (degradationEnabled && e.hasSalvageablePartial()) {
                log.warn("[AgentDispatcher] Agent [{}] hit an unhealable functional gap for run [{}] ({}ms) "
                                + "— delivering a partial artifact + coverage report (COMPLETED_WITH_GAPS). Gaps: {}",
                        agent.getAgentName(), runId, elapsedMs, e.getGaps());
                // Not a client-visible error: a delivered, degraded product. Logged as non-error with the gaps.
                persistenceService.logAgentExecution(
                        runId, agent.getAgentName(),
                        "[DEGRADED] Delivered partial artifact with gaps: " + String.join("; ", e.getGaps()),
                        null, 0, 0, elapsedMs, false);

                Map<Object, Object> vars = machine.getExtendedState().getVariables();
                vars.put(PipelineContextKeys.DEGRADATION_PARTIAL_SOURCE, e.getPartialSource());
                vars.put(PipelineContextKeys.DEGRADATION_FEATURE_MANIFEST, e.getFeatureManifest());
                vars.put(PipelineContextKeys.DEGRADATION_GAPS, e.getGaps());
                sendEvent(machine, MessageBuilder.withPayload(MidasEvent.DEGRADE_ARTIFACT).build());
            } else {
                log.error("[AgentDispatcher] Agent [{}] functional gap for run [{}] ({}ms); degradation {} — "
                                + "failing. {}", agent.getAgentName(), runId, elapsedMs,
                        degradationEnabled ? "had no salvageable partial" : "disabled", e.getMessage());
                persistenceService.logAgentExecution(
                        runId, agent.getAgentName(), e.getMessage(), null, 0, 0, elapsedMs, true);
                sendCriticalFailure(machine, e.getMessage());
            }

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

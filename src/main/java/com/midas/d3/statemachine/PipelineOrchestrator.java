package com.midas.d3.statemachine;

import com.midas.d3.context.MidasContext;
import com.midas.d3.persistence.PersistenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.statemachine.StateMachine;
import org.springframework.statemachine.config.StateMachineFactory;
import org.springframework.statemachine.listener.StateMachineListener;
import org.springframework.statemachine.listener.StateMachineListenerAdapter;
import org.springframework.statemachine.state.State;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thin facade over {@link StateMachineFactory} that manages the lifecycle of
 * per-run state machine instances and exposes a clean API for pipeline controllers.
 *
 * <p><b>Instance management:</b> Each call to {@link #startPipeline} creates a
 * new {@link StateMachine} keyed by {@code pipelineRunId}. Instances persist in
 * memory until {@link #reset} or JVM restart. Phase 4 will add Redis-backed
 * persistence for durability and horizontal scaling.
 *
 * <p><b>Concurrency:</b> {@link ConcurrentHashMap} protects the instance map.
 * Individual machines are NOT thread-safe — callers must ensure a single machine
 * is not driven concurrently from multiple threads.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PipelineOrchestrator {

    private final StateMachineFactory<MidasState, MidasEvent> stateMachineFactory;
    private final PersistenceService persistenceService;

    /** Active machine instances keyed by pipeline run ID. */
    private final ConcurrentHashMap<String, StateMachine<MidasState, MidasEvent>> activeMachines =
            new ConcurrentHashMap<>();

    /** Terminal states after which an auto-mode run's machine can be evicted from the registry. */
    private static final EnumSet<MidasState> TERMINAL_STATES = EnumSet.of(
            MidasState.COMPLETED, MidasState.COMPLETED_WITH_GAPS, MidasState.ERROR);

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Creates and starts a new pipeline run in <em>manual</em> (REST-driven) mode.
     * Agents will NOT be dispatched automatically; callers must submit results via
     * {@link #submitResult}.
     *
     * @param rawUserIdea the raw idea text to process
     * @return the generated pipeline run ID
     */
    public String startPipeline(String rawUserIdea) {
        return startPipelineInternal(rawUserIdea, UUID.randomUUID().toString(), false, null, null, null);
    }

    /**
     * Creates and starts a new pipeline run in <em>auto-drive</em> mode without Telegram.
     * Agents are dispatched automatically on each state entry — same behaviour as the
     * Telegram bot, but without chat/message metadata or a state listener.
     */
    public String startPipelineAuto(String rawUserIdea) {
        return startPipelineInternal(rawUserIdea, UUID.randomUUID().toString(), true, null, null, null);
    }

    /**
     * Creates and starts a new pipeline run in <em>auto-drive</em> (Telegram) mode.
     *
     * <p>The machine is started with {@link PipelineContextKeys#AUTO_MODE_KEY} set to
     * {@code true} so that {@link com.midas.d3.statemachine.action.AgentEntryAction} will
     * automatically dispatch the appropriate agent on each state entry. The optional
     * {@code listener} is attached before the machine starts so it receives the very
     * first state-change notification.
     *
     * @param rawUserIdea       the raw user idea
     * @param telegramChatId    Telegram chat ID (stored in context for traceability)
     * @param telegramMessageId ID of the "initializing…" Telegram message to edit
     * @param listener          optional per-run listener (e.g. {@code TelegramStateListener})
     * @return the generated pipeline run ID
     */
    public String startPipelineWithListener(String rawUserIdea,
                                            long telegramChatId,
                                            int telegramMessageId,
                                            @Nullable StateMachineListener<MidasState, MidasEvent> listener) {
        return startPipelineInternal(rawUserIdea, UUID.randomUUID().toString(),
                true, telegramChatId, telegramMessageId, listener);
    }

    // ── Internal factory ──────────────────────────────────────────────────────

    private String startPipelineInternal(String rawUserIdea,
                                         String runId,
                                         boolean autoMode,
                                         @Nullable Long telegramChatId,
                                         @Nullable Integer telegramMessageId,
                                         @Nullable StateMachineListener<MidasState, MidasEvent> listener) {
        if (rawUserIdea == null || rawUserIdea.isBlank()) {
            throw new IllegalArgumentException("rawUserIdea must not be null or blank.");
        }

        StateMachine<MidasState, MidasEvent> machine = stateMachineFactory.getStateMachine(runId);

        if (listener != null) {
            machine.addStateListener(listener);
        }
        if (autoMode) {
            // Auto-drive runs (Telegram/auto) have no client to DELETE/reset them, so without this the
            // StateMachine + MidasContext would stay pinned in the registry forever (heap leak / OOM) and
            // the reaper would skip them (hasActiveMachine==true), leaving stuck auto runs un-reapable.
            // REST runs deliberately do NOT get this listener: clients must still GET status/context/
            // artifacts after a terminal state, until they explicitly reset the run.
            machine.addStateListener(new TerminalEvictionListener(runId));
        }

        machine.startReactively().block();

        MessageBuilder<MidasEvent> builder = MessageBuilder
                .withPayload(MidasEvent.START)
                .setHeader(PipelineContextKeys.RAW_IDEA_HEADER, rawUserIdea.strip())
                .setHeader(PipelineContextKeys.RUN_ID_HEADER, runId)
                .setHeader(PipelineContextKeys.AUTO_MODE_HEADER, autoMode);

        if (telegramChatId != null) {
            builder.setHeader(PipelineContextKeys.TELEGRAM_CHAT_ID_HEADER, telegramChatId);
        }
        if (telegramMessageId != null) {
            builder.setHeader(PipelineContextKeys.TELEGRAM_MESSAGE_ID_HEADER, telegramMessageId);
        }

        sendEvent(machine, builder.build());

        activeMachines.put(runId, machine);

        // Persist the new run record — non-blocking; DB failures do not abort the pipeline.
        persistenceService.createRun(runId, rawUserIdea.strip(), telegramChatId);

        log.info("[PipelineOrchestrator] Run [{}] started (autoMode={}, state={}).",
                runId, autoMode, currentState(machine));
        return runId;
    }

    /**
     * Submits an LLM-produced JSON string for validation at the current stage.
     *
     * @param runId     the pipeline run ID returned by {@link #startPipeline}
     * @param llmOutput raw JSON string from the LLM
     * @throws PipelineNotFoundException if no machine exists for {@code runId}
     */
    public void submitResult(String runId, String llmOutput) {
        StateMachine<MidasState, MidasEvent> machine = requireMachine(runId);

        sendEvent(machine, MessageBuilder
                .withPayload(MidasEvent.SUBMIT_RESULT)
                .setHeader(PipelineContextKeys.LLM_OUTPUT_HEADER, llmOutput)
                .build());

        log.info("[PipelineOrchestrator] Run [{}] submitted result. State now: {}",
                runId, currentState(machine));
    }

    /**
     * Returns the current public {@link MidasState} of a pipeline run.
     * CHOICE pseudo-states (internal SSM routing nodes) are never returned;
     * instead the last known non-CHOICE state is read from the context.
     *
     * @throws PipelineNotFoundException if no machine exists for {@code runId}
     */
    public MidasState getState(String runId) {
        MidasState raw = currentState(requireMachine(runId));
        return isChoiceState(raw) ? getPublicStateFromContext(requireMachine(runId)) : raw;
    }

    private boolean isChoiceState(MidasState state) {
        return state != null && state.name().endsWith("_CHOICE");
    }

    private MidasState getPublicStateFromContext(StateMachine<MidasState, MidasEvent> machine) {
        Object pending = machine.getExtendedState().getVariables().get(PipelineContextKeys.PENDING_STAGE);
        return (pending instanceof MidasState s) ? s : currentState(machine);
    }

    /**
     * Returns the {@link MidasContext} snapshot stored in the machine's ExtendedState.
     *
     * @throws PipelineNotFoundException if no machine exists for {@code runId}
     */
    public Optional<MidasContext> getContext(String runId) {
        StateMachine<MidasState, MidasEvent> machine = requireMachine(runId);
        Object raw = machine.getExtendedState().getVariables().get(PipelineContextKeys.MIDAS_CONTEXT);
        return (raw instanceof MidasContext ctx) ? Optional.of(ctx) : Optional.empty();
    }

    /**
     * Resets a pipeline from ERROR or COMPLETED back to IDLE, and removes the
     * machine from the active registry.
     *
     * @throws PipelineNotFoundException if no machine exists for {@code runId}
     */
    public void reset(String runId) {
        StateMachine<MidasState, MidasEvent> machine = requireMachine(runId);
        sendEvent(machine, MessageBuilder.withPayload(MidasEvent.RESET).build());
        machine.stopReactively().block();
        activeMachines.remove(runId);
        log.info("[PipelineOrchestrator] Run [{}] reset and removed.", runId);
    }

    /**
     * Sends a {@link MidasEvent#CRITICAL_FAILURE} event to the machine, causing an
     * immediate transition to {@link MidasState#ERROR}. Called by async agent tasks when
     * a {@link com.midas.d3.agent.base.AgentExecutionException} is caught.
     *
     * <p>Safe to call from any thread.
     *
     * @param runId        the pipeline run ID
     * @param errorMessage human-readable failure description stored in {@link com.midas.d3.context.MidasContext}
     */
    public void criticalFailure(String runId, String errorMessage) {
        StateMachine<MidasState, MidasEvent> machine = requireMachine(runId);
        sendEvent(machine, MessageBuilder
                .withPayload(MidasEvent.CRITICAL_FAILURE)
                .setHeader(PipelineContextKeys.AGENT_ERROR_HEADER,
                        errorMessage != null ? errorMessage : "Agent failure — no details")
                .build());
        log.error("[PipelineOrchestrator] Run [{}] CRITICAL_FAILURE sent. State now: {}",
                runId, currentState(machine));
    }

    /**
     * Resumes a pipeline that is paused in {@link MidasState#WAITING_FOR_USER_INPUT}.
     *
     * <p>The user's reply text is appended to the pipeline's {@code rawUserIdea} so the
     * System Analyst receives the enriched input on its next invocation. The
     * {@link MidasEvent#USER_REPLIED} event is then dispatched to move the machine back
     * to {@link MidasState#SYSTEM_ANALYSIS}.
     *
     * @param runId     the pipeline run ID
     * @param replyText the raw clarification text provided by the user
     * @throws PipelineNotFoundException if no machine exists for {@code runId}
     * @throws IllegalStateException     if the machine is not in {@code WAITING_FOR_USER_INPUT}
     */
    public void userReplied(String runId, String replyText) {
        StateMachine<MidasState, MidasEvent> machine = requireMachine(runId);

        MidasState current = currentState(machine);
        if (current != MidasState.WAITING_FOR_USER_INPUT) {
            throw new IllegalStateException(
                    "Run [%s] is not waiting for user input (current state: %s).".formatted(runId, current));
        }

        Map<Object, Object> vars = machine.getExtendedState().getVariables();
        MidasContext ctx = (MidasContext) vars.get(PipelineContextKeys.MIDAS_CONTEXT);
        if (ctx != null && replyText != null && !replyText.isBlank()) {
            String enriched = ctx.getRawUserIdea()
                    + "\n\n[УТОЧНЕНИЕ ОТ ПОЛЬЗОВАТЕЛЯ]\n"
                    + replyText.strip();
            vars.put(PipelineContextKeys.MIDAS_CONTEXT, ctx.withRawUserIdea(enriched));
            // Persist the enriched idea so the DB record reflects the full clarified input.
            persistenceService.updateRunIdea(runId, enriched);
        }

        sendEvent(machine, MessageBuilder
                .withPayload(MidasEvent.USER_REPLIED)
                .setHeader(PipelineContextKeys.USER_REPLY_HEADER,
                        replyText != null ? replyText.strip() : "")
                .build());

        log.info("[PipelineOrchestrator] Run [{}] resumed after user reply. State now: {}",
                runId, currentState(machine));
    }

    /** Returns the count of currently active (not yet reset) pipeline runs. */
    public int activeRunCount() {
        return activeMachines.size();
    }

    public boolean hasActiveMachine(String runId) {
        return runId != null && !runId.isBlank() && activeMachines.containsKey(runId);
    }

    /**
     * Removes a run's machine from the active registry and stops it off the caller's thread. Idempotent
     * (a no-op if the run was already evicted/reset). Called by the auto-mode terminal-eviction listener;
     * package-private so lifecycle behaviour can be unit-tested.
     */
    void evictRun(String runId) {
        StateMachine<MidasState, MidasEvent> machine = activeMachines.remove(runId);
        if (machine == null) {
            return;
        }
        // Stop off-thread: this is invoked from inside the machine's own stateEntered callback, so a
        // synchronous stopReactively().block() here could deadlock the transition.
        CompletableFuture.runAsync(() -> {
            try {
                machine.stopReactively().block();
            } catch (Exception e) {
                log.warn("[PipelineOrchestrator] Error stopping evicted machine [{}]: {}", runId, e.getMessage());
            }
        });
        log.info("[PipelineOrchestrator] Run [{}] evicted from active registry.", runId);
    }

    /**
     * State listener attached ONLY to auto-mode runs: evicts the machine once it enters a terminal state
     * so completed/errored auto runs do not leak in the registry and stuck ones become reapable.
     */
    private final class TerminalEvictionListener extends StateMachineListenerAdapter<MidasState, MidasEvent> {
        private final String runId;

        private TerminalEvictionListener(String runId) {
            this.runId = runId;
        }

        @Override
        public void stateEntered(State<MidasState, MidasEvent> state) {
            if (state != null && TERMINAL_STATES.contains(state.getId())) {
                log.info("[PipelineOrchestrator] Auto-mode run [{}] reached terminal [{}] — evicting.",
                        runId, state.getId());
                evictRun(runId);
            }
        }
    }

    // ── Internal helpers ─────────────────────────────────────────────────────

    private StateMachine<MidasState, MidasEvent> requireMachine(String runId) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId must not be null or blank.");
        }
        StateMachine<MidasState, MidasEvent> machine = activeMachines.get(runId);
        if (machine == null) {
            throw new PipelineNotFoundException("No active pipeline run found for ID: " + runId);
        }
        return machine;
    }

    private void sendEvent(StateMachine<MidasState, MidasEvent> machine,
                           org.springframework.messaging.Message<MidasEvent> msg) {
        machine.sendEvent(Mono.just(msg)).blockLast();
    }

    private MidasState currentState(StateMachine<MidasState, MidasEvent> machine) {
        return machine.getState() != null ? machine.getState().getId() : null;
    }

    // ── Exception ─────────────────────────────────────────────────────────────

    public static final class PipelineNotFoundException extends RuntimeException {
        public PipelineNotFoundException(String message) {
            super(message);
        }
    }
}

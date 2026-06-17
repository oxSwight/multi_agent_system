package com.midas.d3.statemachine;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe registry that tracks pipeline runs currently paused and waiting for
 * user input ({@link MidasState#WAITING_FOR_USER_INPUT}).
 *
 * <p>This lightweight service exists to break the circular dependency that would
 * arise if {@link com.midas.d3.statemachine.action.PauseForInputAction} injected
 * {@link PipelineOrchestrator} directly (which is itself created by the
 * {@link org.springframework.statemachine.config.StateMachineFactory} wired in
 * {@link com.midas.d3.statemachine.PipelineStateMachineConfig}).
 *
 * <h2>Lifecycle</h2>
 * <ol>
 *   <li>{@link com.midas.d3.statemachine.action.PauseForInputAction} calls
 *       {@link #register(long, String)} when the machine enters
 *       {@code WAITING_FOR_USER_INPUT}.</li>
 *   <li>{@link com.midas.d3.telegram.TelegramPipelineBot} calls
 *       {@link #resolve(long)} when it receives a Reply message from the user.</li>
 *   <li>{@link PipelineOrchestrator#userReplied(String, String)} calls
 *       {@link #clear(long)} once the {@link MidasEvent#USER_REPLIED} event has been dispatched.</li>
 * </ol>
 */
@Slf4j
@Service
public class HumanInTheLoopRegistry {

    /** Maps Telegram chat ID → pipeline run ID for runs awaiting user clarification. */
    private final ConcurrentHashMap<Long, String> waitingRuns = new ConcurrentHashMap<>();

    /**
     * Registers a pipeline run as waiting for user input on the given Telegram chat.
     *
     * @param chatId Telegram chat ID
     * @param runId  pipeline run ID
     */
    public void register(long chatId, String runId) {
        waitingRuns.put(chatId, runId);
        log.info("[HumanInTheLoopRegistry] Run [{}] registered as waiting for chat [{}].", runId, chatId);
    }

    /**
     * Resolves the pipeline run ID that is currently waiting for input on the given chat.
     *
     * @param chatId Telegram chat ID
     * @return an {@link Optional} containing the run ID, or empty if none is waiting
     */
    public Optional<String> resolve(long chatId) {
        return Optional.ofNullable(waitingRuns.get(chatId));
    }

    /**
     * Removes the waiting registration for the given chat, typically called after the
     * {@link MidasEvent#USER_REPLIED} event has been successfully dispatched.
     *
     * @param chatId Telegram chat ID
     */
    public void clear(long chatId) {
        String removed = waitingRuns.remove(chatId);
        if (removed != null) {
            log.info("[HumanInTheLoopRegistry] Cleared waiting run [{}] for chat [{}].", removed, chatId);
        }
    }

    /** Returns {@code true} if any run is currently waiting for the given chat. */
    public boolean isWaiting(long chatId) {
        return waitingRuns.containsKey(chatId);
    }
}

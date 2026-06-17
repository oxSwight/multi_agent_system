package com.midas.d3.statemachine;

/**
 * Typed constants for all keys used in:
 * <ul>
 *   <li>{@link org.springframework.statemachine.ExtendedState} variables map</li>
 *   <li>Spring Messaging {@link org.springframework.messaging.Message} headers</li>
 * </ul>
 *
 * Centralizing these strings eliminates magic-string bugs across guards and actions.
 */
public final class PipelineContextKeys {

    private PipelineContextKeys() {}

    // ── ExtendedState variable keys ──────────────────────────────────────────

    /** Key for the {@link com.midas.d3.context.MidasContext} stored in ExtendedState. */
    public static final String MIDAS_CONTEXT = "MIDAS_CONTEXT";

    /**
     * Key for the last successfully validated {@link com.fasterxml.jackson.databind.JsonNode}.
     * Written by {@link com.midas.d3.statemachine.guard.ValidationPassedGuard}
     * and consumed by {@link com.midas.d3.statemachine.action.StoreArtifactAction}.
     */
    public static final String LAST_VALIDATED_NODE = "LAST_VALIDATED_NODE";

    /** Key for the last validation error message. Used for diagnostic logging. */
    public static final String LAST_VALIDATION_ERROR = "LAST_VALIDATION_ERROR";

    /**
     * Key for the processing stage that triggered the current CHOICE routing.
     * Written by {@link com.midas.d3.statemachine.action.ValidateAndCaptureAction}
     * and consumed by {@link com.midas.d3.statemachine.action.StoreArtifactAction}.
     */
    public static final String PENDING_STAGE = "PENDING_STAGE";

    // ── Message header keys ──────────────────────────────────────────────────

    /** Header: raw user idea string. Required on {@link MidasEvent#START} messages. */
    public static final String RAW_IDEA_HEADER = "rawIdea";

    /** Header: pipeline run identifier. Required on {@link MidasEvent#START} messages. */
    public static final String RUN_ID_HEADER = "runId";

    /**
     * Header: raw JSON string output from the LLM.
     * Required on {@link MidasEvent#SUBMIT_RESULT} messages.
     */
    public static final String LLM_OUTPUT_HEADER = "llmOutput";

    // ── Telegram integration ─────────────────────────────────────────────────

    /** Header: Telegram chat ID ({@code Long}). Carried on {@link MidasEvent#START} when bot-initiated. */
    public static final String TELEGRAM_CHAT_ID_HEADER = "telegramChatId";

    /** Header: Telegram progress message ID ({@code Integer}). Carried on {@link MidasEvent#START} when bot-initiated. */
    public static final String TELEGRAM_MESSAGE_ID_HEADER = "telegramMessageId";

    /**
     * ExtendedState key: {@code Boolean} flag indicating whether the pipeline was
     * started in auto-drive mode (Telegram) so {@link com.midas.d3.statemachine.action.AgentEntryAction}
     * knows to dispatch agents automatically.
     */
    public static final String AUTO_MODE_KEY = "AUTO_MODE";

    /** Header: {@code Boolean} auto-mode flag carried on the {@link MidasEvent#START} message. */
    public static final String AUTO_MODE_HEADER = "autoMode";

    /**
     * Header: human-readable agent error message carried on {@link MidasEvent#CRITICAL_FAILURE} events.
     */
    public static final String AGENT_ERROR_HEADER = "agentErrorMessage";

    // ── Human-in-the-Loop ────────────────────────────────────────────────────

    /**
     * ExtendedState key: raw {@code [NEED_INFO]} text returned by the System Analyst.
     * Written by {@link com.midas.d3.statemachine.action.AgentEntryAction} and consumed
     * by {@link com.midas.d3.statemachine.action.PauseForInputAction}.
     */
    public static final String ANALYST_QUESTIONS_KEY = "ANALYST_QUESTIONS";

    /**
     * Header: the user's clarification reply text carried on {@link MidasEvent#USER_REPLIED} events.
     */
    public static final String USER_REPLY_HEADER = "userReply";
}

package com.midas.d3.statemachine;

/**
 * Events that drive the MIDAS_D3 state machine.
 *
 * <p>Lifecycle:
 * <pre>
 *   START          — kicks off a pipeline run (IDLE → SYSTEM_ANALYSIS)
 *   SUBMIT_RESULT  — submits an LLM response for validation at the current stage
 *                    Three internal transitions compete on this event per stage:
 *                    1. [guard: validation passed]  → advance to next stage
 *                    2. [guard: retries exhausted]  → ERROR
 *                    3. [no guard / fallback]        → stay, increment retry counter
 *   RESET          — clears ERROR or COMPLETED, returns machine to IDLE for reuse
 * </pre>
 */
public enum MidasEvent {

    /**
     * Begin a new pipeline run. Must carry headers:
     * <ul>
     *   <li>{@code PipelineContextKeys.RAW_IDEA_HEADER} — user's raw idea string</li>
     *   <li>{@code PipelineContextKeys.RUN_ID_HEADER}   — unique run identifier</li>
     * </ul>
     */
    START,

    /**
     * Submit the LLM output for the current stage. Must carry header:
     * <ul>
     *   <li>{@code PipelineContextKeys.LLM_OUTPUT_HEADER} — raw JSON string from LLM</li>
     * </ul>
     */
    SUBMIT_RESULT,

    /**
     * Reset the machine to IDLE from either COMPLETED or ERROR.
     */
    RESET,

    /**
     * Signals that a {@link com.midas.d3.agent.base.BaseMidasAgent} exhausted all internal
     * retries and threw {@link com.midas.d3.agent.base.AgentExecutionException}.
     * Triggers an immediate transition to {@link MidasState#ERROR} from any processing state,
     * bypassing the normal CHOICE-based retry routing.
     *
     * <p>Must carry header {@link com.midas.d3.statemachine.PipelineContextKeys#AGENT_ERROR_HEADER}
     * with a human-readable error description.
     */
    CRITICAL_FAILURE,

    /**
     * Fired by {@link com.midas.d3.statemachine.AgentDispatcher} when {@link MidasState#CODE_GENERATION}
     * hits an unhealable functional gap yet a best-effort partial artifact is salvageable and graceful
     * degradation is enabled. Triggers {@code CODE_GENERATION → COMPLETED_WITH_GAPS}: the pipeline
     * delivers the partial source plus a coverage report instead of a client-visible CRITICAL FAILURE.
     *
     * <p>The partial payload (source, feature manifest, gaps) is passed via ExtendedState variables
     * ({@link com.midas.d3.statemachine.PipelineContextKeys#DEGRADATION_PARTIAL_SOURCE} et al.), not
     * message headers, so {@link com.midas.d3.statemachine.action.DegradeToGapsAction} can read it.
     */
    DEGRADE_ARTIFACT,

    /**
     * Fired by {@link com.midas.d3.statemachine.action.AgentEntryAction} when the System Analyst
     * returns a response prefixed with {@code [NEED_INFO]}, indicating the user's idea is too
     * ambiguous to proceed. Triggers {@code SYSTEM_ANALYSIS → WAITING_FOR_USER_INPUT}.
     */
    ANALYST_NEEDS_INFO,

    /**
     * Fired by the Telegram bot when the user replies with clarifying information.
     * Triggers {@code WAITING_FOR_USER_INPUT → SYSTEM_ANALYSIS} so the analyst
     * can retry with the enriched context.
     *
     * <p>Must carry header {@link com.midas.d3.statemachine.PipelineContextKeys#USER_REPLY_HEADER}
     * with the user's raw reply text.
     */
    USER_REPLIED
}

package com.midas.d3.eval;

/**
 * Terminal classification of a single eval run.
 *
 * <p>The eval-harness measures the reliability of the sold product, so the taxonomy is framed
 * around what a paying customer would actually experience:
 * <ul>
 *   <li>{@link #PASS} / {@link #PARTIAL} — an artifact was <em>delivered</em> (full, or degraded with an
 *       honest coverage report). This is the graceful outcome the north-star demands.</li>
 *   <li>{@link #FAIL} — the pipeline reached {@code ERROR}: the client-visible crash we are trying to
 *       drive to zero (principle: "never CRITICAL FAILURE in the client's face").</li>
 *   <li>{@link #TIMEOUT} / {@link #CLIENT_ERROR} — the harness could not observe a terminal outcome; not
 *       a product verdict, but recorded so the numbers stay honest.</li>
 * </ul>
 */
public enum EvalOutcome {

    /** Reached {@code COMPLETED} — the sold happy path. */
    PASS,

    /** Reached {@code COMPLETED_WITH_GAPS} — graceful degradation: partial artifact + coverage report. */
    PARTIAL,

    /** Reached {@code ERROR} — the client-visible failure we are trying to eliminate. */
    FAIL,

    /** Did not reach any terminal state within the per-run time budget. */
    TIMEOUT,

    /** The harness itself could not drive/observe the run (network / HTTP / parse error). */
    CLIENT_ERROR;

    /**
     * Terminal pipeline states as reported by {@code GET /status}. {@code COMPLETED_WITH_GAPS} is
     * included proactively — it does not exist yet (it is the target of the graceful-degradation task)
     * but classifying it now means the harness needs no change when that state lands.
     */
    public static boolean isTerminal(String state) {
        return "COMPLETED".equals(state)
                || "COMPLETED_WITH_GAPS".equals(state)
                || "ERROR".equals(state);
    }

    public static EvalOutcome classify(String terminalState, boolean timedOut, boolean clientError) {
        if (clientError) return CLIENT_ERROR;
        if (timedOut) return TIMEOUT;
        if (terminalState == null) return CLIENT_ERROR;
        return switch (terminalState) {
            case "COMPLETED" -> PASS;
            case "COMPLETED_WITH_GAPS" -> PARTIAL;
            case "ERROR" -> FAIL;
            default -> TIMEOUT; // still running / unknown non-terminal at deadline
        };
    }

    /** True when the delivered artifact is usable by a paying customer (full or degraded). */
    public boolean isDelivered() {
        return this == PASS || this == PARTIAL;
    }
}

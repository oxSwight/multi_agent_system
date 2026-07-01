package com.midas.d3.eval;

/**
 * Immutable record of one eval run's observed outcome and economics.
 *
 * <p>Assembled from three read-only REST snapshots ({@code /status}, {@code /context},
 * {@code /dashboard/runs/{id}}) — the harness never instruments the pipeline, it only observes what
 * the product already exposes.
 *
 * @param stageReached  for a delivered run: {@code COMPLETED}; for a failure: the stage the pipeline
 *                      died at (last ERROR audit entry), so the report can show <em>where</em> reliability
 *                      breaks down, not just that it did.
 * @param latencyMs     harness wall-clock from submit to terminal state (provider-clock-independent).
 * @param qualityOverall deterministic {@code qualityScore.overall} (F4), null when absent.
 * @param verdict        Controller verdict (PASS / PASS_WITH_NOTES / REJECT), null when the gate never ran.
 * @param failureReason  {@code lastErrorMessage} or the failing audit detail; null on a delivered run.
 */
public record EvalRunResult(
        String ideaId,
        String kind,
        String expected,
        String runId,
        String terminalState,
        EvalOutcome outcome,
        String stageReached,
        long latencyMs,
        int promptTokens,
        int completionTokens,
        Double costUsd,
        Double qualityOverall,
        String verdict,
        int remediationAttempts,
        String failureReason
) {

    public int totalTokens() {
        return promptTokens + completionTokens;
    }

    public double latencySeconds() {
        return latencyMs / 1000.0;
    }

    /**
     * Did the run match its golden-set expectation?
     * <ul>
     *   <li>{@code DEGRADE}-expected ideas: satisfied when <em>delivered</em> (full or partial) — a
     *       graceful degradation is a success for an over-scoped idea, an {@code ERROR} is not.</li>
     *   <li>{@code PASS}-expected ideas: satisfied only by a full {@link EvalOutcome#PASS}.</li>
     * </ul>
     */
    public boolean metExpectation() {
        if ("DEGRADE".equalsIgnoreCase(expected)) {
            return outcome.isDelivered();
        }
        return outcome == EvalOutcome.PASS;
    }
}

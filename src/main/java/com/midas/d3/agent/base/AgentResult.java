package com.midas.d3.agent.base;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;

/**
 * Immutable result produced by a {@link BaseMidasAgent} execution.
 *
 * <p>Two variants exist:
 * <ul>
 *   <li><b>Normal</b> — {@code validatedOutput} is non-null; the agent produced a valid JSON artifact.</li>
 *   <li><b>NeedsInfo</b> — {@code validatedOutput} is {@code null}; {@code rawLlmOutput} starts with
 *       {@code [NEED_INFO]} and contains clarifying questions from the analyst.
 *       Detected via {@link #isNeedsInfo()}.</li>
 * </ul>
 *
 * @param validatedOutput the GoalKeeper-validated {@link JsonNode} artifact; {@code null} for NeedsInfo responses
 * @param rawLlmOutput    the raw text returned by the LLM (JSON for normal, questions for NeedsInfo)
 * @param attemptsUsed    how many LLM calls were needed (1 = first try succeeded)
 */
public record AgentResult(
        JsonNode validatedOutput,
        String   rawLlmOutput,
        int      attemptsUsed
) {
    /** Prefix the System Analyst emits when it requires additional input from the user. */
    public static final String NEED_INFO_PREFIX = "[NEED_INFO]";

    public AgentResult {
        // validatedOutput is intentionally nullable for NEED_INFO responses
        Objects.requireNonNull(rawLlmOutput, "rawLlmOutput must not be null");
        if (attemptsUsed < 1) {
            throw new IllegalArgumentException("attemptsUsed must be >= 1, got: " + attemptsUsed);
        }
    }

    /**
     * Factory for Human-in-the-Loop responses where the LLM returned questions
     * instead of a JSON artifact.
     *
     * @param questionsText full raw text from the LLM (must start with {@value #NEED_INFO_PREFIX})
     * @param attempt       attempt number (1-based)
     */
    public static AgentResult needsInfo(String questionsText, int attempt) {
        Objects.requireNonNull(questionsText, "questionsText must not be null");
        return new AgentResult(null, questionsText, attempt);
    }

    /**
     * Returns {@code true} when the LLM signalled that clarification is needed.
     * In this case {@link #validatedOutput()} is {@code null} and {@link #rawLlmOutput()}
     * contains the questions text.
     */
    public boolean isNeedsInfo() {
        return rawLlmOutput.stripLeading().startsWith(NEED_INFO_PREFIX);
    }

    /** Returns {@code true} if the agent succeeded on the very first LLM call. */
    public boolean succeededOnFirstAttempt() {
        return attemptsUsed == 1;
    }
}

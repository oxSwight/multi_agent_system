package com.midas.d3.agent;

import com.midas.d3.llm.LlmCallException;
import com.midas.d3.llm.LlmCallResult;
import com.midas.d3.validation.ValidationHookException;

import java.util.List;

/**
 * Retry limits for agent LLM loops (parse vs schema/business vs MAX_TOKENS).
 *
 * <ul>
 *   <li>JSON parse error — at most 1 retry (2 total attempts by default)</li>
 *   <li>{@link ValidationHookException} (schema/business) — up to 3 attempts by default</li>
 *   <li>{@code finishReason=MAX_TOKENS} — 0 retries (fail fast)</li>
 * </ul>
 *
 * <p>Limits are configurable via {@link AgentRetryPolicyProperties}; defaults apply in unit tests
 * without a Spring context.
 */
public final class AgentRetryPolicy {

    private static volatile int maxParseAttempts = 2;
    private static volatile int maxValidationAttempts = 3;

    /** @deprecated use {@link #maxValidationAttempts()} */
    @Deprecated
    public static final int MAX_VALIDATION_ATTEMPTS = 3;

    /** @deprecated use {@link #maxParseAttempts()} */
    @Deprecated
    public static final int MAX_PARSE_ATTEMPTS = 2;

    private AgentRetryPolicy() {}

    public static void configure(int parseMaxAttempts, int validationMaxAttempts) {
        if (parseMaxAttempts < 1) {
            throw new IllegalArgumentException("parseMaxAttempts must be >= 1, got: " + parseMaxAttempts);
        }
        if (validationMaxAttempts < 1) {
            throw new IllegalArgumentException("validationMaxAttempts must be >= 1, got: " + validationMaxAttempts);
        }
        maxParseAttempts = parseMaxAttempts;
        maxValidationAttempts = validationMaxAttempts;
    }

    public static int maxParseAttempts() {
        return maxParseAttempts;
    }

    public static int maxValidationAttempts() {
        return maxValidationAttempts;
    }

    public static int maxAttemptsFor(ValidationHookException exception) {
        return exception.isParseError() ? maxParseAttempts : maxValidationAttempts;
    }

    public static boolean canRetry(ValidationHookException exception, int attempt) {
        return attempt < maxAttemptsFor(exception);
    }

    /** Returns at most 3 violation messages for correction feedback prompts. */
    public static String formatViolationsForFeedback(ValidationHookException exception) {
        List<String> violations = exception.getViolations();
        if (violations.isEmpty()) {
            return exception.getMessage();
        }
        int limit = Math.min(3, violations.size());
        String joined = String.join(" | ", violations.subList(0, limit));
        if (violations.size() > 3) {
            joined += " | ... and " + (violations.size() - 3) + " more violation(s)";
        }
        return joined;
    }

    public static String formatViolationsForFeedback(String joinedViolations) {
        if (joinedViolations == null || joinedViolations.isBlank()) {
            return "";
        }
        String[] parts = joinedViolations.split(" \\| ");
        if (parts.length <= 3) {
            return joinedViolations;
        }
        return String.join(" | ", List.of(parts[0], parts[1], parts[2]))
                + " | ... and " + (parts.length - 3) + " more violation(s)";
    }

    public static void failFastIfTruncated(LlmCallResult result, String agentName, String runId) {
        if (!result.isTruncated()) {
            return;
        }
        throw new LlmCallException(
                "LLM response truncated (MAX_TOKENS) for agent [%s] run [%s] — "
                        + "retry is useless without reducing scope".formatted(agentName, runId),
                -1, false);
    }
}

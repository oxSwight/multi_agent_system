package com.midas.d3.llm;

/**
 * Thrown when an LLM call fails due to network errors, timeouts, rate limits,
 * or non-2xx HTTP responses.
 *
 * <p>Carries {@link #isRetryable()} so callers can decide whether to retry
 * or immediately escalate to the pipeline error state.
 */
public final class LlmCallException extends RuntimeException {

    private final int    httpStatus;
    private final boolean retryable;

    public LlmCallException(String message, int httpStatus, boolean retryable) {
        super(message);
        this.httpStatus = httpStatus;
        this.retryable  = retryable;
    }

    public LlmCallException(String message, Throwable cause, boolean retryable) {
        super(message, cause);
        this.httpStatus = -1;
        this.retryable  = retryable;
    }

    public int getHttpStatus()  { return httpStatus; }
    public boolean isRetryable(){ return retryable;  }

    // ── Factory methods ───────────────────────────────────────────────────────

    public static LlmCallException timeout(String agentName) {
        return new LlmCallException(
                "LLM call timed out for agent [" + agentName + "]", -1, true);
    }

    public static LlmCallException rateLimited(String agentName) {
        return new LlmCallException(
                "LLM rate limit hit for agent [" + agentName + "]", 429, true);
    }

    public static LlmCallException rateLimitExhausted(String provider, String agentName) {
        return new LlmCallException(
                "%s API Rate Limit Exceeded for agent [%s]".formatted(provider, agentName),
                429, false);
    }

    public static LlmCallException serverError(String agentName, int status) {
        return new LlmCallException(
                "LLM server error %d for agent [%s]".formatted(status, agentName), status, status >= 500);
    }

    public static LlmCallException emptyResponse(String agentName) {
        return new LlmCallException(
                "LLM returned empty response for agent [" + agentName + "]", 200, false);
    }
}

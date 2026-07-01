package com.midas.d3.llm;

import org.springframework.core.io.buffer.DataBufferLimitException;

/**
 * Shared classification helpers for low-level LLM transport errors that both {@code LlmClient}
 * implementations ({@code NousRestClient}, {@code GeminiLlmClient}) must handle identically.
 */
public final class LlmErrorClassifier {

    private LlmErrorClassifier() {}

    /**
     * {@code true} when the throwable (or any cause) is a {@link DataBufferLimitException} — the reactive
     * codec's in-memory buffer limit was exceeded by an oversized LLM response (typically a large
     * CODE_GENERATION / TEST_GENERATION body).
     *
     * <p>This is <b>not</b> retryable: re-issuing the identical request yields the same oversized body,
     * so retrying only burns the retry budget before the pipeline dies with a client-visible
     * CRITICAL_FAILURE. Callers must fail fast with an actionable message (raise the buffer limit or
     * reduce scope) instead of classifying it as a transient error.
     */
    public static boolean isBufferLimitError(Throwable t) {
        Throwable cause = t;
        while (cause != null) {
            if (cause instanceof DataBufferLimitException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }
}

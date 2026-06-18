package com.midas.d3.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("LlmCallException Tests")
class LlmCallExceptionTest {

    @Test
    @DisplayName("rateLimitExhausted is non-retryable with HTTP 429 and clear provider message")
    void rateLimitExhausted_isNonRetryableWithClearMessage() {
        LlmCallException ex = LlmCallException.rateLimitExhausted("Gemini", "SystemAnalystAgent");

        assertThat(ex.getHttpStatus()).isEqualTo(429);
        assertThat(ex.isRetryable()).isFalse();
        assertThat(ex.getMessage()).contains("Gemini API Rate Limit Exceeded");
        assertThat(ex.getMessage()).contains("SystemAnalystAgent");
    }

    @Test
    @DisplayName("rateLimited remains retryable for transient client-level retries")
    void rateLimited_isRetryable() {
        LlmCallException ex = LlmCallException.rateLimited("SystemAnalystAgent");

        assertThat(ex.getHttpStatus()).isEqualTo(429);
        assertThat(ex.isRetryable()).isTrue();
    }
}

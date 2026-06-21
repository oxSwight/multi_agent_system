package com.midas.d3.agent;

import com.midas.d3.llm.LlmCallResult;
import com.midas.d3.validation.ValidationHookException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("AgentRetryPolicy")
class AgentRetryPolicyTest {

    @AfterEach
    void resetDefaults() {
        AgentRetryPolicy.configure(2, 3);
    }

    @Test
    @DisplayName("parse error allows at most one retry (2 total attempts)")
    void parseError_maxTwoAttempts() {
        ValidationHookException parseError = new ValidationHookException(
                "Agent", "STAGE", List.of("JSON parse error: Unexpected character"));

        assertThat(AgentRetryPolicy.maxAttemptsFor(parseError)).isEqualTo(2);
        assertThat(AgentRetryPolicy.canRetry(parseError, 1)).isTrue();
        assertThat(AgentRetryPolicy.canRetry(parseError, 2)).isFalse();
    }

    @Test
    @DisplayName("schema validation error allows up to three attempts")
    void validationError_maxThreeAttempts() {
        ValidationHookException validationError = new ValidationHookException(
                "Agent", "STAGE", List.of("Missing required field: business_goal"));

        assertThat(AgentRetryPolicy.maxAttemptsFor(validationError)).isEqualTo(3);
        assertThat(AgentRetryPolicy.canRetry(validationError, 1)).isTrue();
        assertThat(AgentRetryPolicy.canRetry(validationError, 2)).isTrue();
        assertThat(AgentRetryPolicy.canRetry(validationError, 3)).isFalse();
    }

    @Test
    @DisplayName("MAX_TOKENS fails fast without retry")
    void maxTokens_failsFast() {
        LlmCallResult truncated = LlmCallResult.of(
                "partial", "model", 10, 5, LlmCallResult.FINISH_REASON_MAX_TOKENS);

        assertThatThrownBy(() -> AgentRetryPolicy.failFastIfTruncated(truncated, "TestAgent", "run-1"))
                .isInstanceOf(com.midas.d3.llm.LlmCallException.class)
                .hasMessageContaining("MAX_TOKENS");
    }

    @Test
    @DisplayName("configure applies custom limits from properties")
    void configure_customLimits() {
        AgentRetryPolicy.configure(1, 5);

        assertThat(AgentRetryPolicy.maxParseAttempts()).isEqualTo(1);
        assertThat(AgentRetryPolicy.maxValidationAttempts()).isEqualTo(5);
    }
}

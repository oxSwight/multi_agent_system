package com.midas.d3.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBufferLimitException;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("LlmErrorClassifier Tests")
class LlmErrorClassifierTest {

    @Test
    @DisplayName("direct DataBufferLimitException → true")
    void directBufferLimit_isTrue() {
        assertThat(LlmErrorClassifier.isBufferLimitError(
                new DataBufferLimitException("exceeded"))).isTrue();
    }

    @Test
    @DisplayName("DataBufferLimitException wrapped in a cause chain → true")
    void wrappedBufferLimit_isTrue() {
        Throwable wrapped = new RuntimeException("outer",
                new IllegalStateException("mid", new DataBufferLimitException("exceeded")));
        assertThat(LlmErrorClassifier.isBufferLimitError(wrapped)).isTrue();
    }

    @Test
    @DisplayName("unrelated error → false")
    void unrelated_isFalse() {
        assertThat(LlmErrorClassifier.isBufferLimitError(
                new RuntimeException(new IOException("socket")))).isFalse();
    }

    @Test
    @DisplayName("null → false (no NPE)")
    void nullThrowable_isFalse() {
        assertThat(LlmErrorClassifier.isBufferLimitError(null)).isFalse();
    }
}

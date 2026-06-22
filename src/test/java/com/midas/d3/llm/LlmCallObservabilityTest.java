package com.midas.d3.llm;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("LlmCallObservability")
class LlmCallObservabilityTest {

    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger(LlmCallObservability.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger(LlmCallObservability.class);
        logger.detachAppender(appender);
    }

    @Test
    @DisplayName("logTelemetry emits unified per-call summary with tokens and finish reason")
    void logTelemetry_includesTokensAndFinishReason() {
        LlmCallResult result = LlmCallResult.of("{}", "qwen2.5-coder:14b", 512, 128, "stop");

        LlmCallObservability.logTelemetry("run-1", "SystemAnalystAgent", 1, 3, result);

        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getFormattedMessage())
                .contains("[LLM Telemetry]")
                .contains("Agent: SystemAnalystAgent")
                .contains("Tokens: 512/128")
                .contains("Finish Reason: stop")
                .contains("attempt 1/3");
    }

    @Test
    @DisplayName("logExecutionSummary emits end-of-pass accumulated telemetry")
    void logExecutionSummary_accumulatedTotals() {
        LlmCallObservability.logExecutionSummary("run-2", "ImplementationEngineerAgent", 1200, 400, "stop");

        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getFormattedMessage())
                .contains("[LLM Telemetry]")
                .contains("Agent: ImplementationEngineerAgent")
                .contains("Tokens: 1200/400")
                .contains("Finish Reason: stop");
    }
}

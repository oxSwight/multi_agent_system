package com.midas.d3.llm;

import com.midas.d3.statemachine.MidasState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("GeminiLlmClient Rate Limit Tests")
class GeminiLlmClientTest {

    private static final String SUCCESS_BODY = """
            {"candidates":[{"content":{"parts":[{"text":"ok"}]}}]}
            """;

    @Test
    @DisplayName("Fails fast after max rapid 429 retries with clear rate-limit message")
    void call_persistent429_throwsRateLimitExhausted() {
        AtomicInteger calls = new AtomicInteger();
        ExchangeFunction exchange = request -> {
            calls.incrementAndGet();
            return Mono.error(WebClientResponseException.create(
                    HttpStatus.TOO_MANY_REQUESTS.value(),
                    "Too Many Requests",
                    HttpHeaders.EMPTY,
                    "quota exceeded".getBytes(StandardCharsets.UTF_8),
                    StandardCharsets.UTF_8));
        };

        GeminiLlmClient client = newClient(exchange);

        LlmCallRequest request = requestForRun("run-rate-limit-001");

        assertThatThrownBy(() -> client.call(request))
                .isInstanceOf(LlmCallException.class)
                .satisfies(thrown -> {
                    LlmCallException ex = (LlmCallException) thrown;
                    assertThat(ex.isRetryable()).isFalse();
                    assertThat(ex.getHttpStatus()).isEqualTo(429);
                    assertThat(ex.getMessage()).contains("Gemini API Rate Limit Exceeded");
                });

        assertThat(calls.get()).isEqualTo(4);
    }

    @Test
    @DisplayName("Recovers when a rapid 429 retry succeeds")
    void call_single429ThenSuccess_returnsText() {
        AtomicInteger calls = new AtomicInteger();
        ExchangeFunction exchange = request -> {
            if (calls.incrementAndGet() == 1) {
                return Mono.error(WebClientResponseException.create(
                        HttpStatus.TOO_MANY_REQUESTS.value(),
                        "Too Many Requests",
                        HttpHeaders.EMPTY,
                        "retry".getBytes(StandardCharsets.UTF_8),
                        StandardCharsets.UTF_8));
            }
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, "application/json")
                    .body(SUCCESS_BODY)
                    .build());
        };

        String text = newClient(exchange).call(requestForRun("run-rate-limit-002"));

        assertThat(text).isEqualTo("ok");
        assertThat(calls.get()).isEqualTo(2);
    }

    private static GeminiLlmClient newClient(ExchangeFunction exchange) {
        return new GeminiLlmClient(
                WebClient.builder().exchangeFunction(exchange),
                "test-api-key",
                "gemini-2.0-flash",
                10,
                0);
    }

    private static LlmCallRequest requestForRun(String runId) {
        return LlmCallRequest.of(
                MidasState.SYSTEM_ANALYSIS,
                "SystemAnalystAgent",
                "system",
                "user",
                runId);
    }
}

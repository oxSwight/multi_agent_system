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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("GeminiLlmClient Rate Limit Tests")
class GeminiLlmClientTest {

    private static final String DEFAULT_MODEL = "gemini-2.5-flash";
    private static final String SUCCESS_BODY = """
            {"candidates":[{"content":{"parts":[{"text":"ok"}]}}],"usageMetadata":{"promptTokenCount":512,"candidatesTokenCount":128,"totalTokenCount":640}}
            """;

    @Test
    @DisplayName("Extracts prompt and completion token counts from usageMetadata")
    void call_successBody_returnsTokenCounts() {
        ExchangeFunction exchange = request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .body(SUCCESS_BODY)
                .build());

        LlmCallResult result = newClient(exchange).call(requestForRun("run-tokens-001"));

        assertThat(result.text()).isEqualTo("ok");
        assertThat(result.modelUsed()).isEqualTo(DEFAULT_MODEL);
        assertThat(result.promptTokens()).isEqualTo(512);
        assertThat(result.completionTokens()).isEqualTo(128);
    }

    @Test
    @DisplayName("Uses default model in URL path when no override is present")
    void call_withoutModelOverride_usesDefaultModelInUrlPath() {
        AtomicReference<String> capturedPath = new AtomicReference<>();
        ExchangeFunction exchange = request -> {
            capturedPath.set(request.url().getPath());
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, "application/json")
                    .body(SUCCESS_BODY)
                    .build());
        };

        LlmCallResult result = newClient(exchange).call(requestForRun("run-default-model"));

        assertThat(capturedPath.get()).contains(DEFAULT_MODEL + ":generateContent");
        assertThat(result.modelUsed()).isEqualTo(DEFAULT_MODEL);
    }

    @Test
    @DisplayName("Uses modelOverride in URL path when present on request")
    void call_withModelOverride_usesOverrideInUrlPath() {
        AtomicReference<String> capturedPath = new AtomicReference<>();
        ExchangeFunction exchange = request -> {
            capturedPath.set(request.url().getPath());
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, "application/json")
                    .body(SUCCESS_BODY)
                    .build());
        };

        LlmCallRequest request = LlmCallRequest.of(
                MidasState.CODE_GENERATION,
                "ImplementationEngineerAgent",
                "system",
                "user",
                "run-model-override-001",
                "gemini-2.5-flash");

        LlmCallResult result = newClient(exchange).call(request);

        assertThat(capturedPath.get()).contains("gemini-2.5-flash:generateContent");
        assertThat(result.modelUsed()).isEqualTo("gemini-2.5-flash");
    }

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

        LlmCallResult result = newClient(exchange).call(requestForRun("run-rate-limit-002"));

        assertThat(result.text()).isEqualTo("ok");
        assertThat(calls.get()).isEqualTo(2);
    }

    private static GeminiLlmClient newClient(ExchangeFunction exchange) {
        return new GeminiLlmClient(
                WebClient.builder().exchangeFunction(exchange),
                "test-api-key",
                DEFAULT_MODEL,
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

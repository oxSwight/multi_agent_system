package com.midas.d3.llm.nous;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.midas.d3.llm.nous.dto.NousRequest;
import com.midas.d3.llm.nous.dto.NousResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.json.Jackson2JsonDecoder;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Live connectivity smoke test for a locally running Ollama instance.
 *
 * <p>This test is intentionally excluded from the standard {@code mvn verify} run
 * (Surefire excludes the {@code integration} JUnit tag). Run it manually once Ollama
 * is confirmed to be serving at {@code http://localhost:11434}:
 *
 * <pre>
 *   # Pull a model first if needed:
 *   ollama pull qwen2.5-coder:14b
 *
 *   # Run just this test (default model):
 *   mvnw test -Dgroups=integration -Dtest=NousRestClientOllamaIT
 *
 *   # Override the model (e.g. a lighter one for low-VRAM machines):
 *   mvnw test -Dgroups=integration -Dtest=NousRestClientOllamaIT -Dollama.test.model=llama3.2:3b
 * </pre>
 *
 * <p>The test uses no Spring context — just a raw {@link WebClient} wired to the same
 * OpenAI-compatible endpoint that {@link NousRestClient} targets in production. If
 * Ollama is not reachable the test is automatically skipped (not failed).
 */
@Tag("integration")
@DisplayName("NousRestClient → local Ollama smoke test")
class NousRestClientOllamaIT {

    private static final String OLLAMA_BASE_URL = "http://localhost:11434";
    private static final String MODEL =
            System.getProperty("ollama.test.model", "qwen2.5-coder:14b");

    private WebClient client;

    @BeforeEach
    void setUpAndCheckReachability() {
        ObjectMapper mapper = new ObjectMapper();
        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(c -> {
                    c.defaultCodecs().jackson2JsonEncoder(new Jackson2JsonEncoder(mapper));
                    c.defaultCodecs().jackson2JsonDecoder(new Jackson2JsonDecoder(mapper));
                    c.defaultCodecs().maxInMemorySize(4 * 1024 * 1024);
                })
                .build();

        client = WebClient.builder()
                .baseUrl(OLLAMA_BASE_URL)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .exchangeStrategies(strategies)
                .build();

        assumeTrue(
                isOllamaReachable(),
                "Ollama is not running at " + OLLAMA_BASE_URL + " — skipping. "
                + "Start Ollama and pull a model: ollama pull " + MODEL);
    }

    @Test
    @DisplayName("returns a non-blank text response for a minimal chat-completion request")
    void chatCompletion_returnsNonBlankText() {
        NousRequest request = NousRequest.of(
                MODEL,
                "You are a concise assistant.",
                "Reply with exactly the string: MIDAS_SMOKE_TEST_OK");

        NousResponse response = client.post()
                .uri("/v1/chat/completions")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(NousResponse.class)
                .timeout(Duration.ofSeconds(120))
                .block();

        assertThat(response).as("Ollama returned a null response body").isNotNull();

        Optional<String> text = response.extractText();
        assertThat(text).as("Response choices contained no extractable text").isPresent();
        assertThat(text.get()).as("Response text must not be blank").isNotBlank();

        System.out.printf("[OllamaIT] model=%s | finish=%s | tokens(p/c)=%d/%d | reply(%d chars): %s%n",
                MODEL,
                response.extractFinishReason().orElse("?"),
                response.extractPromptTokens(),
                response.extractCompletionTokens(),
                text.get().length(),
                text.get().substring(0, Math.min(120, text.get().length())));
    }

    private boolean isOllamaReachable() {
        try {
            String body = WebClient.builder()
                    .baseUrl(OLLAMA_BASE_URL)
                    .build()
                    .get()
                    .uri("/api/tags")
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(3))
                    .block();
            return body != null && !body.isBlank();
        } catch (Exception e) {
            return false;
        }
    }
}

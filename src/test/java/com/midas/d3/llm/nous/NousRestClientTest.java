package com.midas.d3.llm.nous;

import com.midas.d3.llm.LlmCallRequest;
import com.midas.d3.llm.LlmCallResult;
import com.midas.d3.statemachine.MidasState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("NousRestClient model routing")
class NousRestClientTest {

    private static final String SUCCESS_BODY = """
            {"choices":[{"message":{"content":"ok"},"finish_reason":"stop"}]}
            """;

    @Test
    @DisplayName("Extracts prompt and completion tokens when Ollama returns usage block")
    void call_withUsageBlock_extractsTokenCounts() {
        String body = """
                {"choices":[{"message":{"content":"ok"},"finish_reason":"stop"}],
                 "usage":{"prompt_tokens":256,"completion_tokens":64,"total_tokens":320}}
                """;
        ExchangeFunction exchange = request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .body(body)
                .build());

        NousRestClient client = newClient(exchange);

        LlmCallRequest request = LlmCallRequest.of(
                MidasState.SYSTEM_ANALYSIS,
                "SystemAnalystAgent",
                "system",
                "user",
                "run-tokens");

        LlmCallResult result = client.call(request);

        assertThat(result.promptTokens()).isEqualTo(256);
        assertThat(result.completionTokens()).isEqualTo(64);
        assertThat(result.finishReason()).isEqualTo("stop");
    }

    @Test
    @DisplayName("Falls back to Ollama reasoning field when content is empty")
    void call_emptyContent_usesReasoningField() {
        String body = """
                {"choices":[{"message":{"role":"assistant","content":"","reasoning":"{\\"verdict\\":\\"PASS\\"}"},"finish_reason":"stop"}]}
                """;
        ExchangeFunction exchange = request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .body(body)
                .build());

        NousRestClient client = newClient(exchange, routingProperties(true, Map.of(
                "ControllerAgent", "qwen2.5-coder:14b"
        )));

        LlmCallRequest request = LlmCallRequest.of(
                MidasState.PRODUCT_REVIEW,
                "ControllerAgent",
                "system",
                "user",
                "run-reasoning-fallback");

        LlmCallResult result = client.call(request);

        assertThat(result.text()).contains("\"verdict\":\"PASS\"");
        assertThat(result.modelUsed()).isEqualTo("qwen2.5-coder:14b");
    }

    @Test
    @DisplayName("Per-call override wins over a routing.agents pin (LlmModelPolicy is the single source)")
    void call_routingEnabled_override_winsOverAgentPin() {
        // ControllerAgent is pinned to qwen in routing.agents, but the per-call override carries the
        // LlmModelPolicy decision — which is authoritative and must win.
        NousRestClient client = newClient(okExchange());

        LlmCallRequest request = LlmCallRequest.of(
                MidasState.PRODUCT_REVIEW,
                "ControllerAgent",
                "system",
                "user",
                "run-routing-001",
                "deepseek-r1:8b");

        LlmCallResult result = client.call(request);

        assertThat(result.modelUsed()).isEqualTo("deepseek-r1:8b");
    }

    @Test
    @DisplayName("routing.agents pin applies only when the call carries no override")
    void call_routingEnabled_noOverride_usesAgentPin() {
        NousRestClient client = newClient(okExchange());

        // No model override → routing.agents pin for ControllerAgent applies.
        LlmCallRequest request = LlmCallRequest.of(
                MidasState.PRODUCT_REVIEW,
                "ControllerAgent",
                "system",
                "user",
                "run-routing-001b");

        LlmCallResult result = client.call(request);

        assertThat(result.modelUsed()).isEqualTo("qwen2.5-coder:14b");
    }

    @Test
    @DisplayName("Falls back to default-model for unmapped agents when routing is enabled")
    void call_routingEnabled_unmappedAgent_usesDefaultModel() {
        NousRestClient client = newClient(okExchange(), routingProperties(true, Map.of(
                "ControllerAgent", "qwen2.5-coder:14b"
        )));

        LlmCallRequest request = LlmCallRequest.of(
                MidasState.INTEGRATION_STRATEGY,
                "IntegrationEngineerAgent",
                "system",
                "user",
                "run-routing-002");

        LlmCallResult result = client.call(request);

        assertThat(result.modelUsed()).isEqualTo("qwen2.5-coder:14b");
    }

    @Test
    @DisplayName("Routing enabled: an unmapped agent honors the per-call model override before the default")
    void call_routingEnabled_unmappedAgent_honorsOverride() {
        // ControllerAgent is the only pinned agent; IntegrationEngineerAgent is unmapped, so the
        // LlmModelPolicy tier decision carried as modelOverride must win over routing.default-model.
        NousRestClient client = newClient(okExchange(), routingProperties(true, Map.of(
                "ControllerAgent", "qwen2.5-coder:14b"
        )));

        LlmCallRequest request = LlmCallRequest.of(
                MidasState.INTEGRATION_STRATEGY,
                "IntegrationEngineerAgent",
                "system",
                "user",
                "run-routing-override",
                "fast-tier-1b");

        LlmCallResult result = client.call(request);

        assertThat(result.modelUsed()).isEqualTo("fast-tier-1b");
    }

    @Test
    @DisplayName("Uses modelOverride when routing is disabled")
    void call_routingDisabled_usesModelOverride() {
        NousRestClient client = newClient(okExchange(), routingProperties(false, Map.of()));

        LlmCallRequest request = LlmCallRequest.of(
                MidasState.CODE_GENERATION,
                "ImplementationEngineerAgent",
                "system",
                "user",
                "run-routing-003",
                "custom-model:7b");

        LlmCallResult result = client.call(request);

        assertThat(result.modelUsed()).isEqualTo("custom-model:7b");
    }

    private static ExchangeFunction okExchange() {
        return request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .body(SUCCESS_BODY)
                .build());
    }

    private NousRestClient newClient(ExchangeFunction exchange) {
        return newClient(exchange, routingProperties(true, Map.of(
                "ControllerAgent", "qwen2.5-coder:14b",
                "SystemAnalystAgent", "qwen2.5-coder:14b"
        )));
    }

    private NousRestClient newClient(ExchangeFunction exchange, NousProperties properties) {
        WebClient.Builder builder = WebClient.builder().exchangeFunction(exchange);
        return new NousRestClient(builder, properties, 16);
    }

    private NousProperties routingProperties(boolean enabled, Map<String, String> agents) {
        NousProperties properties = new NousProperties();
        properties.setBaseUrl("http://localhost:11434");
        properties.setModel("legacy-global-model");
        properties.setTimeoutSeconds(5);
        properties.setHttpMaxRetries(0);
        NousProperties.Routing routing = new NousProperties.Routing();
        routing.setEnabled(enabled);
        routing.setDefaultModel("qwen2.5-coder:14b");
        routing.setAgents(agents);
        properties.setRouting(routing);
        return properties;
    }
}

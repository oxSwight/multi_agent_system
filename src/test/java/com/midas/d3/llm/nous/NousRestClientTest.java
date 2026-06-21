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
    @DisplayName("Routes mapped reasoning agent to DeepSeek when routing is enabled")
    void call_routingEnabled_mappedAgent_usesAgentModel() {
        NousRestClient client = newClient(okExchange());

        LlmCallRequest request = LlmCallRequest.of(
                MidasState.PRODUCT_REVIEW,
                "ControllerAgent",
                "system",
                "user",
                "run-routing-001",
                "qwen2.5-coder:7b");

        LlmCallResult result = client.call(request);

        assertThat(result.modelUsed()).isEqualTo("deepseek-r1:8b");
    }

    @Test
    @DisplayName("Falls back to default-model for unmapped agents when routing is enabled")
    void call_routingEnabled_unmappedAgent_usesDefaultModel() {
        NousRestClient client = newClient(okExchange(), routingProperties(true, Map.of(
                "ControllerAgent", "deepseek-r1:8b"
        )));

        LlmCallRequest request = LlmCallRequest.of(
                MidasState.INTEGRATION_STRATEGY,
                "IntegrationEngineerAgent",
                "system",
                "user",
                "run-routing-002");

        LlmCallResult result = client.call(request);

        assertThat(result.modelUsed()).isEqualTo("qwen2.5-coder:7b");
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
                "ControllerAgent", "deepseek-r1:8b",
                "ImplementationEngineerAgent", "qwen2.5-coder:7b"
        )));
    }

    private NousRestClient newClient(ExchangeFunction exchange, NousProperties properties) {
        WebClient.Builder builder = WebClient.builder().exchangeFunction(exchange);
        return new NousRestClient(builder, properties);
    }

    private NousProperties routingProperties(boolean enabled, Map<String, String> agents) {
        NousProperties properties = new NousProperties();
        properties.setBaseUrl("http://localhost:11434");
        properties.setModel("legacy-global-model");
        properties.setTimeoutSeconds(5);
        properties.setHttpMaxRetries(0);
        NousProperties.Routing routing = new NousProperties.Routing();
        routing.setEnabled(enabled);
        routing.setDefaultModel("qwen2.5-coder:7b");
        routing.setAgents(agents);
        properties.setRouting(routing);
        return properties;
    }
}

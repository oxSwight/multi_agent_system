package com.midas.d3.llm.nous;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Typed configuration for {@link NousRestClient} — OpenAI-compatible LLM backends
 * (Ollama, OpenRouter, LM Studio).
 *
 * <p>When {@link Routing#enabled} is {@code true}, each agent invocation is routed
 * to a model from {@link Routing#agents}; unlisted agents fall back to
 * {@link Routing#defaultModel}.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "midas.nous")
public class NousProperties {

    private String baseUrl = "http://localhost:1234";
    private String apiKey = "";
    private String model = "nous-hermes-2-mistral-7b-dpo";
    private int timeoutSeconds = 120;
    private int httpMaxRetries = 2;
    private Routing routing = new Routing();

    @Getter
    @Setter
    public static class Routing {

        private boolean enabled = false;
        private String defaultModel = "qwen2.5-coder:7b";
        private Map<String, String> agents = new LinkedHashMap<>();
    }
}

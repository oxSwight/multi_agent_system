package com.midas.d3.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.midas.d3.agent.implementation.ExtensionWiringNormalizer;
import com.midas.d3.config.JacksonConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Closes the exact end-to-end failures observed on live gpt-4o-mini runs: the model satisfied every
 * functional criterion but tripped a P0 reference gate because a generated reference pointed a
 * directory away — first a popup {@code <script src="sidebar.js">} (file at {@code src/sidebar.js}),
 * then a manifest {@code "service_worker": "background.js"} (file at {@code src/background.js}). Proves
 * — without an LLM in the loop — that the deterministic {@link ExtensionWiringNormalizer} repairs the
 * artifact so the same assembled-envelope validation now PASSES.
 */
@DisplayName("Extension wiring gate ↔ deterministic normalizer")
class ExtensionWiringGateIntegrationTest {

    private ObjectMapper mapper;
    private ImplementationEngineerValidator validator;

    @BeforeEach
    void setUp() {
        mapper = new JacksonConfig().objectMapper();
        validator = new ImplementationEngineerValidator(mapper, new FeatureManifestValidator());
    }

    @Test
    @DisplayName("Misresolved popup <script src> fails the gate before normalization, passes after")
    void normalizerClosesHtmlWiringDeadEnd() throws Exception {
        JsonNode spec = mapper.readTree("""
                {"business_goal": "autofill", "core_features": ["fill"]}
                """);
        JsonNode architecture = mapper.readTree("""
                {"file_layout": ["frontend/popup.html", "frontend/src/sidebar.js"], "api_contracts": []}
                """);
        ObjectNode source = mapper.createObjectNode();
        source.put("frontend/popup.html",
                "<html><body><div id=\"app\"></div><script src=\"sidebar.js\"></script></body></html>");
        source.put("frontend/src/sidebar.js", "export const init = () => {};");

        assertThatThrownBy(() -> validator.validateWithTechnicalSpec(envelope(source, "frontend/src/sidebar.js"), spec, architecture))
                .isInstanceOf(ValidationHookException.class)
                .satisfies(t -> assertThat(((ValidationHookException) t).getViolations())
                        .anyMatch(v -> v.contains("sidebar.js") && v.contains("missing")));

        JsonNode normalized = ExtensionWiringNormalizer.normalize(source, mapper);
        assertThat(normalized.get("frontend/popup.html").asText()).contains("src=\"src/sidebar.js\"");
        assertThatCode(() -> validator.validateWithTechnicalSpec(envelope(normalized, "frontend/src/sidebar.js"), spec, architecture))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Misresolved manifest service_worker fails the gate before normalization, passes after")
    void normalizerClosesManifestWiringDeadEnd() throws Exception {
        JsonNode spec = mapper.readTree("""
                {"business_goal": "autofill", "core_features": ["fill"]}
                """);
        JsonNode architecture = mapper.readTree("""
                {"file_layout": ["frontend/manifest.json", "frontend/src/background.js"], "api_contracts": []}
                """);
        ObjectNode source = mapper.createObjectNode();
        source.put("frontend/manifest.json",
                "{\"manifest_version\":3,\"name\":\"X\",\"background\":{\"service_worker\":\"background.js\"}}");
        source.put("frontend/src/background.js", "self.addEventListener('install', () => {});");

        assertThatThrownBy(() -> validator.validateWithTechnicalSpec(envelope(source, "frontend/src/background.js"), spec, architecture))
                .isInstanceOf(ValidationHookException.class)
                .satisfies(t -> assertThat(((ValidationHookException) t).getViolations())
                        .anyMatch(v -> v.contains("background.js") && v.contains("missing")));

        JsonNode normalized = ExtensionWiringNormalizer.normalize(source, mapper);
        assertThat(mapper.readTree(normalized.get("frontend/manifest.json").asText())
                .at("/background/service_worker").asText()).isEqualTo("src/background.js");
        assertThatCode(() -> validator.validateWithTechnicalSpec(envelope(normalized, "frontend/src/background.js"), spec, architecture))
                .doesNotThrowAnyException();
    }

    private String envelope(JsonNode source, String implFile) throws Exception {
        ObjectNode env = mapper.createObjectNode();
        env.set("source_files", source);
        ObjectNode entry = (ObjectNode) env.putArray("feature_manifest").addObject();
        entry.put("feature_id", "fill");
        entry.put("feature_name", "fill");
        entry.set("files", mapper.createArrayNode().add(implFile));
        entry.set("entry_points", mapper.createArrayNode().add("main"));
        return mapper.writeValueAsString(env);
    }
}

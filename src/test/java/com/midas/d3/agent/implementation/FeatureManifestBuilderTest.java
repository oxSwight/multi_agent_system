package com.midas.d3.agent.implementation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.midas.d3.config.JacksonConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("FeatureManifestBuilder")
class FeatureManifestBuilderTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new JacksonConfig().objectMapper();
    }

    @Test
    @DisplayName("single core_feature receives all source paths")
    void build_singleFeature_allFiles() throws Exception {
        var spec = objectMapper.readTree("""
                {"core_features":["Popup UI"]}
                """);
        var sources = objectMapper.readTree("""
                {"manifest.json":"{}","src/popup.ts":"export const x=1;"}
                """);

        var manifest = FeatureManifestBuilder.build(spec, sources, objectMapper, false, null);

        assertThat(manifest).hasSize(1);
        assertThat(manifest.get(0).get("feature_id").asText()).isEqualTo("popup-ui");
        assertThat(manifest.get(0).get("files")).hasSize(2);
    }

    @Test
    @DisplayName("HYBRID partial pass uses surface-specific feature_id")
    void build_hybridPartial_surfaceId() throws Exception {
        var sources = objectMapper.readTree("""
                {"manifest.json":"{}"}
                """);

        var manifest = FeatureManifestBuilder.build(
                objectMapper.createObjectNode(), sources, objectMapper, true, ImplementationSurface.SERVER);

        assertThat(manifest.get(0).get("feature_id").asText()).isEqualTo("server-surface");
    }

    @Test
    @DisplayName("deriveEntryPoint strips extension from path")
    void deriveEntryPoint_stripsExtension() {
        assertThat(FeatureManifestBuilder.deriveEntryPoint("src/main/java/App.java")).isEqualTo("App");
        assertThat(FeatureManifestBuilder.deriveEntryPoint("manifest.json")).isEqualTo("manifest");
    }
}

package com.midas.d3.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.midas.d3.config.JacksonConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImplementationOutputUnwrapperTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new JacksonConfig().objectMapper();
    }

    @Test
    void unwrap_validEnvelope_returnsSourceFilesAndManifest() throws Exception {
        var envelope = objectMapper.readTree("""
                {
                  "source_files": {
                    "src/App.java": "public class App {}"
                  },
                  "feature_manifest": [
                    {
                      "feature_id": "run-app",
                      "feature_name": "Run app",
                      "files": ["src/App.java"],
                      "entry_points": ["App"]
                    }
                  ]
                }
                """);

        var unwrapped = ImplementationOutputUnwrapper.unwrap(envelope);

        assertThat(unwrapped.sourceFiles().has("src/App.java")).isTrue();
        assertThat(unwrapped.featureManifest()).hasSize(1);
        assertThat(unwrapped.featureManifest().get(0).get("feature_id").asText()).isEqualTo("run-app");
    }

    @Test
    void unwrap_missingSourceFiles_throwsIllegalArgumentException() throws Exception {
        var envelope = objectMapper.readTree("""
                {"feature_manifest": []}
                """);

        assertThatThrownBy(() -> ImplementationOutputUnwrapper.unwrap(envelope))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("source_files");
    }

    @Test
    void unwrap_missingFeatureManifest_throwsIllegalArgumentException() throws Exception {
        var envelope = objectMapper.readTree("""
                {"source_files": {"src/App.java": "public class App {}"}}
                """);

        assertThatThrownBy(() -> ImplementationOutputUnwrapper.unwrap(envelope))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("feature_manifest");
    }

    @Test
    void unwrap_sourceFilesNotObject_throwsIllegalArgumentException() throws Exception {
        var envelope = objectMapper.readTree("""
                {
                  "source_files": ["src/App.java"],
                  "feature_manifest": []
                }
                """);

        assertThatThrownBy(() -> ImplementationOutputUnwrapper.unwrap(envelope))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("source_files");
    }

    @Test
    void unwrap_nullEnvelope_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> ImplementationOutputUnwrapper.unwrap(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

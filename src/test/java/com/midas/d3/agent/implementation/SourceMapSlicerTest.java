package com.midas.d3.agent.implementation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.midas.d3.config.JacksonConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SourceMapSlicer")
class SourceMapSlicerTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new JacksonConfig().objectMapper();
    }

    @Test
    @DisplayName("client slice keeps JS/TS/manifest paths only")
    void slice_clientSurface_filtersServerPaths() throws Exception {
        var source = objectMapper.readTree("""
                {
                  "manifest.json":"{}",
                  "src/popup.ts":"export const ok = true;",
                  "src/main/java/com/example/App.java":"public class App {}"
                }
                """);

        var sliced = SourceMapSlicer.slice(source, ImplementationSurface.CLIENT, objectMapper);

        assertThat(sliced.size()).isEqualTo(2);
        assertThat(sliced.has("manifest.json")).isTrue();
        assertThat(sliced.has("src/popup.ts")).isTrue();
        assertThat(sliced.has("src/main/java/com/example/App.java")).isFalse();
    }

    @Test
    @DisplayName("server slice keeps Java and backend config paths only")
    void slice_serverSurface_filtersClientPaths() throws Exception {
        var source = objectMapper.readTree("""
                {
                  "manifest.json":"{}",
                  "src/popup.ts":"export const ok = true;",
                  "src/main/java/com/example/App.java":"public class App {}",
                  "src/main/resources/application.yml":"server:\\n  port: 8080"
                }
                """);

        var sliced = SourceMapSlicer.slice(source, ImplementationSurface.SERVER, objectMapper);

        assertThat(sliced.size()).isEqualTo(2);
        assertThat(sliced.has("src/main/java/com/example/App.java")).isTrue();
        assertThat(sliced.has("src/main/resources/application.yml")).isTrue();
        assertThat(sliced.has("manifest.json")).isFalse();
        assertThat(sliced.has("src/popup.ts")).isFalse();
    }

    @Test
    @DisplayName("null or non-object input yields empty map")
    void slice_invalidInput_returnsEmptyMap() {
        var sliced = SourceMapSlicer.slice(null, ImplementationSurface.CLIENT, objectMapper);
        assertThat(sliced.isEmpty()).isTrue();
    }
}

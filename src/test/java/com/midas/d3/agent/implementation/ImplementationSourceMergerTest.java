package com.midas.d3.agent.implementation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.midas.d3.config.JacksonConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ImplementationSourceMerger")
class ImplementationSourceMergerTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new JacksonConfig().objectMapper();
    }

    @Test
    @DisplayName("merge combines disjoint client and server file maps")
    void merge_disjointPaths_producesUnifiedMap() throws Exception {
        var client = objectMapper.readTree("""
                {"manifest.json":"{}", "src/popup.ts":"export const x = 1;"}
                """);
        var server = objectMapper.readTree("""
                {"src/main/java/com/example/App.java":"public class App {}"}
                """);

        var merged = ImplementationSourceMerger.merge(client, server, objectMapper);

        assertThat(merged.size()).isEqualTo(3);
        assertThat(merged.has("manifest.json")).isTrue();
        assertThat(merged.has("src/main/java/com/example/App.java")).isTrue();
    }

    @Test
    @DisplayName("merge rejects duplicate file paths across passes")
    void merge_duplicatePath_throwsMergeException() throws Exception {
        var client = objectMapper.readTree("""
                {"shared/config.json":"client"}
                """);
        var server = objectMapper.readTree("""
                {"shared/config.json":"server"}
                """);

        assertThatThrownBy(() -> ImplementationSourceMerger.merge(client, server, objectMapper))
                .isInstanceOf(ImplementationSourceMerger.ImplementationMergeException.class)
                .hasMessageContaining("duplicate file path");
    }

    @Test
    @DisplayName("merge rejects empty pass output")
    void merge_emptyPass_throwsMergeException() throws Exception {
        var client = objectMapper.readTree("{}");
        var server = objectMapper.readTree("""
                {"App.java":"class App {}"}
                """);

        assertThatThrownBy(() -> ImplementationSourceMerger.merge(client, server, objectMapper))
                .isInstanceOf(ImplementationSourceMerger.ImplementationMergeException.class)
                .hasMessageContaining("empty source map");
    }
}

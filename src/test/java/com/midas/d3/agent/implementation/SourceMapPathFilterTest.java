package com.midas.d3.agent.implementation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.midas.d3.config.JacksonConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("SourceMapPathFilter")
class SourceMapPathFilterTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new JacksonConfig().objectMapper();
    }

    @Test
    @DisplayName("filter extracts only the requested paths from source map")
    void filter_subsetPaths_returnsOnlyRequestedEntries() throws Exception {
        JsonNode sourceMap = objectMapper.readTree("""
                {
                  "src/App.java": "public class App {}",
                  "src/Service.java": "public class Service {}",
                  "src/Util.java": "public class Util {}",
                  "src/Config.java": "public class Config {}"
                }
                """);

        JsonNode filtered = SourceMapPathFilter.filter(
                sourceMap,
                List.of("src/App.java", "src/Service.java"),
                objectMapper);

        assertThat(filtered.size()).isEqualTo(2);
        assertThat(filtered.has("src/App.java")).isTrue();
        assertThat(filtered.has("src/Service.java")).isTrue();
        assertThat(filtered.has("src/Util.java")).isFalse();
        assertThat(filtered.has("src/Config.java")).isFalse();
    }

    @Test
    @DisplayName("filter skips paths not present in source map without error")
    void filter_pathNotInSourceMap_silentlySkipped() throws Exception {
        JsonNode sourceMap = objectMapper.readTree("""
                {"src/App.java": "public class App {}"}
                """);

        JsonNode filtered = SourceMapPathFilter.filter(
                sourceMap,
                List.of("src/App.java", "src/NonExistent.java"),
                objectMapper);

        assertThat(filtered.size()).isEqualTo(1);
        assertThat(filtered.has("src/App.java")).isTrue();
        assertThat(filtered.has("src/NonExistent.java")).isFalse();
    }

    @Test
    @DisplayName("filter with empty path list returns empty map")
    void filter_emptyPathList_returnsEmptyMap() throws Exception {
        JsonNode sourceMap = objectMapper.readTree("""
                {"src/App.java": "public class App {}"}
                """);

        JsonNode filtered = SourceMapPathFilter.filter(sourceMap, List.of(), objectMapper);

        assertThat(filtered.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("filter with blank path entries silently skips them")
    void filter_blankPathEntries_silentlySkipped() throws Exception {
        JsonNode sourceMap = objectMapper.readTree("""
                {"src/App.java": "public class App {}"}
                """);

        JsonNode filtered = SourceMapPathFilter.filter(
                sourceMap,
                List.of("src/App.java", "  ", ""),
                objectMapper);

        assertThat(filtered.size()).isEqualTo(1);
        assertThat(filtered.has("src/App.java")).isTrue();
    }

    @Test
    @DisplayName("non-object source map returns empty result")
    void filter_nonObjectSourceMap_returnsEmpty() throws Exception {
        JsonNode arrayNode = objectMapper.readTree("[\"a\",\"b\"]");

        JsonNode filtered = SourceMapPathFilter.filter(
                arrayNode,
                List.of("src/App.java"),
                objectMapper);

        assertThat(filtered.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("null sourceMap → throws NullPointerException")
    void filter_nullSourceMap_throwsNullPointer() {
        assertThatThrownBy(() -> SourceMapPathFilter.filter(null, List.of("a"), objectMapper))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("null paths → throws NullPointerException")
    void filter_nullPaths_throwsNullPointer() throws Exception {
        JsonNode sourceMap = objectMapper.readTree("{\"src/App.java\":\"code\"}");
        assertThatThrownBy(() -> SourceMapPathFilter.filter(sourceMap, null, objectMapper))
                .isInstanceOf(NullPointerException.class);
    }
}

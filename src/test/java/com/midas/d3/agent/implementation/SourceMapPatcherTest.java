package com.midas.d3.agent.implementation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.midas.d3.config.JacksonConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("SourceMapPatcher")
class SourceMapPatcherTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new JacksonConfig().objectMapper();
    }

    @Test
    @DisplayName("happy merge — patch overrides existing files in baseline")
    void apply_happyMerge_patchOverridesBaselineEntries() throws Exception {
        JsonNode baseline = objectMapper.readTree("""
                {
                  "src/App.java": "public class App {}",
                  "src/Service.java": "public class Service {}",
                  "src/Util.java": "public class Util {}"
                }
                """);
        JsonNode patch = objectMapper.readTree("""
                {
                  "src/App.java": "public class App { public static void main(String[] args) {} }",
                  "src/Service.java": "public class Service { public void run() {} }"
                }
                """);

        JsonNode merged = SourceMapPatcher.apply(baseline, patch, objectMapper);

        assertThat(merged.size()).isEqualTo(3);
        assertThat(merged.get("src/App.java").asText())
                .contains("main");
        assertThat(merged.get("src/Service.java").asText())
                .contains("run");
        assertThat(merged.get("src/Util.java").asText())
                .isEqualTo("public class Util {}");
    }

    @Test
    @DisplayName("unknown path — patch references a file not in baseline → throws")
    void apply_unknownPath_throwsPatchValidationException() throws Exception {
        JsonNode baseline = objectMapper.readTree("""
                {"src/App.java": "public class App {}"}
                """);
        JsonNode patch = objectMapper.readTree("""
                {"src/Unknown.java": "public class Unknown {}"}
                """);

        assertThatThrownBy(() -> SourceMapPatcher.apply(baseline, patch, objectMapper))
                .isInstanceOf(PatchValidationException.class)
                .hasMessageContaining("src/Unknown.java");
    }

    @Test
    @DisplayName("empty patch rejection — throws on empty patch map")
    void apply_emptyPatch_throwsPatchValidationException() throws Exception {
        JsonNode baseline = objectMapper.readTree("""
                {"src/App.java": "public class App {}"}
                """);
        JsonNode emptyPatch = objectMapper.readTree("{}");

        assertThatThrownBy(() -> SourceMapPatcher.apply(baseline, emptyPatch, objectMapper))
                .isInstanceOf(PatchValidationException.class)
                .hasMessageContaining("non-empty");
    }

    @Test
    @DisplayName("path traversal in patch key → throws on illegal path")
    void apply_pathTraversal_throwsPatchValidationException() throws Exception {
        JsonNode baseline = objectMapper.readTree("""
                {"src/App.java": "public class App {}"}
                """);
        JsonNode patch = objectMapper.readTree("""
                {"../secrets.txt": "leaked"}
                """);

        assertThatThrownBy(() -> SourceMapPatcher.apply(baseline, patch, objectMapper))
                .isInstanceOf(PatchValidationException.class)
                .hasMessageContaining("traversal");
    }

    @Test
    @DisplayName("null value in patch → throws")
    void apply_nullValueInPatch_throwsPatchValidationException() throws Exception {
        JsonNode baseline = objectMapper.readTree("""
                {"src/App.java": "public class App {}"}
                """);
        JsonNode patch = objectMapper.readTree("""
                {"src/App.java": null}
                """);

        assertThatThrownBy(() -> SourceMapPatcher.apply(baseline, patch, objectMapper))
                .isInstanceOf(PatchValidationException.class)
                .hasMessageContaining("src/App.java");
    }

    @Test
    @DisplayName("null baseline → throws NullPointerException")
    void apply_nullBaseline_throwsNullPointer() throws Exception {
        JsonNode patch = objectMapper.readTree("""
                {"src/App.java": "code"}
                """);
        assertThatThrownBy(() -> SourceMapPatcher.apply(null, patch, objectMapper))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("null patch → throws NullPointerException")
    void apply_nullPatch_throwsNullPointer() throws Exception {
        JsonNode baseline = objectMapper.readTree("""
                {"src/App.java": "public class App {}"}
                """);
        assertThatThrownBy(() -> SourceMapPatcher.apply(baseline, null, objectMapper))
                .isInstanceOf(NullPointerException.class);
    }
}

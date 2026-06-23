package com.midas.d3.build;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("BuildToolDetector")
class BuildToolDetectorTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private com.fasterxml.jackson.databind.JsonNode map(String json) throws Exception {
        return mapper.readTree(json);
    }

    @Test
    @DisplayName("pom.xml anywhere → MAVEN (wins over other descriptors)")
    void detectsMaven() throws Exception {
        assertThat(BuildToolDetector.detect(map("""
                {"pom.xml": "<project/>", "package.json": "{}"}
                """))).isEqualTo(BuildTool.MAVEN);
    }

    @Test
    @DisplayName("build.gradle → GRADLE")
    void detectsGradle() throws Exception {
        assertThat(BuildToolDetector.detect(map("""
                {"build.gradle.kts": "plugins {}"}
                """))).isEqualTo(BuildTool.GRADLE);
    }

    @Test
    @DisplayName("package.json → NPM")
    void detectsNpm() throws Exception {
        assertThat(BuildToolDetector.detect(map("""
                {"frontend/package.json": "{}", "frontend/index.ts": "export {}"}
                """))).isEqualTo(BuildTool.NPM);
    }

    @Test
    @DisplayName("no recognized descriptor → NONE")
    void detectsNone() throws Exception {
        assertThat(BuildToolDetector.detect(map("""
                {"index.html": "<html></html>"}
                """))).isEqualTo(BuildTool.NONE);
    }

    @Test
    @DisplayName("empty / null map → NONE")
    void emptyIsNone() throws Exception {
        assertThat(BuildToolDetector.detect(map("{}"))).isEqualTo(BuildTool.NONE);
        assertThat(BuildToolDetector.detect(null)).isEqualTo(BuildTool.NONE);
    }
}

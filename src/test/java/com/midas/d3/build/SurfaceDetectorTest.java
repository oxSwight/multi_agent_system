package com.midas.d3.build;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SurfaceDetector")
class SurfaceDetectorTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private List<BuildSurface> detect(String json) throws Exception {
        JsonNode map = mapper.readTree(json);
        return SurfaceDetector.detect(map, mapper);
    }

    @Test
    @DisplayName("pom.xml AND package.json → both surfaces (no first-match masking)")
    void detectsBothMavenAndNpm() throws Exception {
        List<BuildSurface> surfaces = detect("""
                {"pom.xml": "<project/>", "package.json": "{}"}
                """);
        assertThat(surfaces).extracting(BuildSurface::kind)
                .containsExactlyInAnyOrder(ProjectKind.MAVEN, ProjectKind.NODE_NPM);
    }

    @Test
    @DisplayName("build.gradle.kts → GRADLE at its directory")
    void detectsGradle() throws Exception {
        List<BuildSurface> surfaces = detect("""
                {"build.gradle.kts": "plugins {}"}
                """);
        assertThat(surfaces).hasSize(1);
        assertThat(surfaces.get(0).kind()).isEqualTo(ProjectKind.GRADLE);
        assertThat(surfaces.get(0).rootDir()).isEqualTo("");
    }

    @Test
    @DisplayName("package.json in subdir → NODE_NPM rooted at that subdir")
    void detectsNpmInSubdir() throws Exception {
        List<BuildSurface> surfaces = detect("""
                {"frontend/package.json": "{}", "frontend/index.ts": "export {}"}
                """);
        assertThat(surfaces).hasSize(1);
        assertThat(surfaces.get(0).kind()).isEqualTo(ProjectKind.NODE_NPM);
        assertThat(surfaces.get(0).rootDir()).isEqualTo("frontend/");
    }

    @Test
    @DisplayName("MV3 manifest.json → CHROME_EXTENSION_MV3 surface")
    void detectsMv3Extension() throws Exception {
        List<BuildSurface> surfaces = detect("""
                {"manifest.json": "{\\"manifest_version\\": 3, \\"name\\": \\"X\\"}",
                 "background.js": "// sw"}
                """);
        assertThat(surfaces).hasSize(1);
        assertThat(surfaces.get(0).kind()).isEqualTo(ProjectKind.CHROME_EXTENSION_MV3);
    }

    @Test
    @DisplayName("Hybrid: backend Maven + frontend MV3 extension → two surfaces at their roots")
    void detectsHybridSurfaces() throws Exception {
        List<BuildSurface> surfaces = detect("""
                {"backend/pom.xml": "<project/>",
                 "backend/src/main/java/App.java": "class App {}",
                 "frontend/manifest.json": "{\\"manifest_version\\": 3}",
                 "frontend/background.js": "// sw"}
                """);
        assertThat(surfaces).extracting(BuildSurface::kind)
                .containsExactlyInAnyOrder(ProjectKind.MAVEN, ProjectKind.CHROME_EXTENSION_MV3);
        assertThat(surfaces).anyMatch(s -> s.kind() == ProjectKind.MAVEN && s.rootDir().equals("backend/"));
        assertThat(surfaces).anyMatch(s ->
                s.kind() == ProjectKind.CHROME_EXTENSION_MV3 && s.rootDir().equals("frontend/"));
    }

    @Test
    @DisplayName("PWA web-app manifest (no manifest_version 3) is NOT an extension surface")
    void pwaManifestIsNotExtension() throws Exception {
        List<BuildSurface> surfaces = detect("""
                {"manifest.json": "{\\"name\\": \\"PWA\\", \\"start_url\\": \\"/\\"}",
                 "index.html": "<html></html>"}
                """);
        assertThat(surfaces).isEmpty();
    }

    @Test
    @DisplayName("No recognized descriptor → no surfaces")
    void noDescriptor_empty() throws Exception {
        assertThat(detect("""
                {"index.html": "<html></html>", "style.css": "body{}"}
                """)).isEmpty();
    }

    @Test
    @DisplayName("empty / null map → no surfaces")
    void emptyOrNull_empty() throws Exception {
        assertThat(detect("{}")).isEmpty();
        assertThat(SurfaceDetector.detect(null, mapper)).isEmpty();
    }
}

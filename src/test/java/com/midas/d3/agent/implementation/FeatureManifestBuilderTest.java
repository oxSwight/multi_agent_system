package com.midas.d3.agent.implementation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.midas.d3.config.JacksonConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

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
    @DisplayName("multi-feature Spring backend maps each feature to its real source file, never pom.xml-only")
    void build_multiFeatureSpringBackend_mapsRealFiles() throws Exception {
        var spec = objectMapper.readTree("""
                {"core_features":[
                  {"id":"project-crud","name":"CRUD operations for projects"},
                  {"id":"task-crud","name":"CRUD operations for tasks"},
                  {"id":"task-filter","name":"Filter tasks by status and project"},
                  {"id":"error-handling","name":"Global exception handling"}
                ]}
                """);
        var sources = objectMapper.readTree("""
                {
                  "backend/pom.xml":"<project/>",
                  "backend/src/main/java/com/example/taskmanager/controller/ProjectController.java":"class ProjectController {}",
                  "backend/src/main/java/com/example/taskmanager/controller/TaskController.java":"class TaskController {}",
                  "backend/src/main/java/com/example/taskmanager/exception/GlobalExceptionHandler.java":"class GlobalExceptionHandler {}",
                  "backend/src/main/java/com/example/taskmanager/model/Task.java":"class Task {}",
                  "backend/src/main/java/com/example/taskmanager/repository/TaskRepository.java":"interface TaskRepository {}"
                }
                """);

        var manifest = FeatureManifestBuilder.build(spec, sources, objectMapper, false, null);

        // Each feature is backed by a plausibly-named real source file — the defect that made the
        // product reviewer falsely reject correct code was these mapping to pom.xml only.
        assertThat(filesOf(manifest, "project-crud")).anyMatch(f -> f.endsWith("ProjectController.java"));
        assertThat(filesOf(manifest, "task-crud")).anyMatch(f -> f.endsWith("TaskController.java"));
        assertThat(filesOf(manifest, "task-filter")).anyMatch(f -> f.endsWith("TaskController.java"));
        assertThat(filesOf(manifest, "error-handling")).anyMatch(f -> f.endsWith("GlobalExceptionHandler.java"));

        // No feature may cite a build descriptor as its sole evidence of implementation.
        for (JsonNode entry : manifest) {
            List<String> files = filesOf(manifest, entry.get("feature_id").asText());
            assertThat(files.size() == 1 && files.get(0).endsWith("pom.xml"))
                    .as("feature '%s' must not be backed by pom.xml alone", entry.get("feature_id").asText())
                    .isFalse();
        }
    }

    private static List<String> filesOf(JsonNode manifest, String featureId) {
        List<String> files = new ArrayList<>();
        for (JsonNode entry : manifest) {
            if (entry.path("feature_id").asText().equals(featureId)) {
                entry.path("files").forEach(f -> files.add(f.asText()));
            }
        }
        return files;
    }

    @Test
    @DisplayName("deriveEntryPoint strips extension from path")
    void deriveEntryPoint_stripsExtension() {
        assertThat(FeatureManifestBuilder.deriveEntryPoint("src/main/java/App.java")).isEqualTo("App");
        assertThat(FeatureManifestBuilder.deriveEntryPoint("manifest.json")).isEqualTo("manifest");
    }
}

package com.midas.d3.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.midas.d3.config.JacksonConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImplementationEngineerValidatorTest {

    private ImplementationEngineerValidator validator;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new JacksonConfig().objectMapper();
        validator = new ImplementationEngineerValidator(objectMapper, new FeatureManifestValidator());
    }

    @Test
    void validate_validEnvelope_returnsNode() throws Exception {
        String json = """
                {
                  "source_files": {
                    "src/App.java": "public class App { public void run() {} }"
                  },
                  "feature_manifest": [
                    {
                      "feature_id": "run-app",
                      "feature_name": "Run app",
                      "files": ["src/App.java"],
                      "entry_points": ["App.run"]
                    }
                  ]
                }
                """;

        JsonNode result = validator.validate(json);

        assertThat(result.get("source_files").has("src/App.java")).isTrue();
        assertThat(result.get("feature_manifest")).hasSize(1);
    }

    @Test
    void validate_flatSourceMap_rejectsLegacyShape() {
        String json = """
                {"src/App.java":"public class App {}"}
                """;

        assertThatThrownBy(() -> validator.validate(json))
                .isInstanceOf(ValidationHookException.class)
                .hasMessageContaining("envelope schema");
    }

    @Test
    void validate_missingSourceFiles_throwsValidationHookException() {
        String json = """
                {
                  "feature_manifest": [
                    {
                      "feature_id": "run-app",
                      "feature_name": "Run app",
                      "files": ["src/App.java"],
                      "entry_points": ["App.run"]
                    }
                  ]
                }
                """;

        assertThatThrownBy(() -> validator.validate(json))
                .isInstanceOf(ValidationHookException.class)
                .hasMessageContaining("source_files");
    }

    @Test
    void validate_manifestFileMissingFromSourceFiles_throwsValidationHookException() {
        String json = """
                {
                  "source_files": {
                    "src/App.java": "public class App {}"
                  },
                  "feature_manifest": [
                    {
                      "feature_id": "run-app",
                      "feature_name": "Run app",
                      "files": ["src/Missing.java"],
                      "entry_points": ["Missing.run"]
                    }
                  ]
                }
                """;

        assertThatThrownBy(() -> validator.validate(json))
                .isInstanceOf(ValidationHookException.class)
                .hasMessageContaining("not present in source_files");
    }

    @Test
    void validate_placeholderInSource_throwsValidationHookException() {
        String json = """
                {
                  "source_files": {
                    "src/App.java": "public class App { // TODO implement }"
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
                """;

        assertThatThrownBy(() -> validator.validate(json))
                .isInstanceOf(ValidationHookException.class)
                .hasMessageContaining("Zero-Placeholder");
    }

    @Test
    void validateWithTechnicalSpec_allCoreFeaturesPresent_passes() throws Exception {
        String json = """
                {
                  "source_files": {
                    "src/task.js": "export function createTask() { return true; }",
                    "src/delete.js": "export function deleteTask() { return true; }"
                  },
                  "feature_manifest": [
                    {
                      "feature_id": "create-task",
                      "feature_name": "Create task",
                      "files": ["src/task.js"],
                      "entry_points": ["createTask"]
                    },
                    {
                      "feature_id": "delete-task",
                      "feature_name": "Delete task",
                      "files": ["src/delete.js"],
                      "entry_points": ["deleteTask"]
                    }
                  ]
                }
                """;
        JsonNode spec = objectMapper.readTree("""
                {"core_features":["Create task","Delete task"]}
                """);

        JsonNode result = validator.validateWithTechnicalSpec(json, spec);

        assertThat(result.get("feature_manifest")).hasSize(2);
    }

    @Test
    void validateWithTechnicalSpec_missingCoreFeature_throwsValidationHookException() throws Exception {
        String json = """
                {
                  "source_files": {
                    "src/task.js": "export function createTask() { return true; }"
                  },
                  "feature_manifest": [
                    {
                      "feature_id": "create-task",
                      "feature_name": "Create task",
                      "files": ["src/task.js"],
                      "entry_points": ["createTask"]
                    }
                  ]
                }
                """;
        JsonNode spec = objectMapper.readTree("""
                {"core_features":["Create task","Delete task"]}
                """);

        assertThatThrownBy(() -> validator.validateWithTechnicalSpec(json, spec))
                .isInstanceOf(ValidationHookException.class)
                .hasMessageContaining("Delete task");
    }

    @Test
    void validateWithTechnicalSpec_orphanManifestId_throwsValidationHookException() throws Exception {
        String json = """
                {
                  "source_files": {
                    "src/task.js": "export function createTask() { return true; }",
                    "src/extra.js": "export function extra() { return true; }"
                  },
                  "feature_manifest": [
                    {
                      "feature_id": "create-task",
                      "feature_name": "Create task",
                      "files": ["src/task.js"],
                      "entry_points": ["createTask"]
                    },
                    {
                      "feature_id": "extra-feature",
                      "feature_name": "Extra feature",
                      "files": ["src/extra.js"],
                      "entry_points": ["extra"]
                    }
                  ]
                }
                """;
        JsonNode spec = objectMapper.readTree("""
                {"core_features":["Create task"]}
                """);

        assertThatThrownBy(() -> validator.validateWithTechnicalSpec(json, spec))
                .isInstanceOf(ValidationHookException.class)
                .hasMessageContaining("extra-feature");
    }

    @Test
    void validateWithTechnicalSpec_objectCoreFeatureIds_crossChecksById() throws Exception {
        String json = """
                {
                  "source_files": {
                    "src/task.js": "export function createTask() { return true; }"
                  },
                  "feature_manifest": [
                    {
                      "feature_id": "feat-001",
                      "feature_name": "Create task",
                      "files": ["src/task.js"],
                      "entry_points": ["createTask"]
                    }
                  ]
                }
                """;
        JsonNode spec = objectMapper.readTree("""
                {"core_features":[{"id":"feat-001","name":"Create task"}]}
                """);

        JsonNode result = validator.validateWithTechnicalSpec(json, spec);

        assertThat(result.get("feature_manifest").get(0).get("feature_id").asText()).isEqualTo("feat-001");
    }

    @Test
    void validateWithTechnicalSpec_textCoreFeature_allowsDifferentIdWhenNameMatches() throws Exception {
        String json = """
                {
                  "source_files": {
                    "src/task.js": "export function createTask() { return true; }"
                  },
                  "feature_manifest": [
                    {
                      "feature_id": "custom-short-id",
                      "feature_name": "Create task",
                      "files": ["src/task.js"],
                      "entry_points": ["createTask"]
                    }
                  ]
                }
                """;
        JsonNode spec = objectMapper.readTree("""
                {"core_features":["Create task"]}
                """);

        JsonNode result = validator.validateWithTechnicalSpec(json, spec);

        assertThat(result.get("feature_manifest")).hasSize(1);
    }

    @Test
    void validateSingleFileOutput_validResponse_returnsParsed() throws Exception {
        var parsed = validator.validateSingleFileOutput("""
                {"path":"src/App.java","content":"public class App {}"}
                """, "src/App.java");

        assertThat(parsed.path()).isEqualTo("src/App.java");
        assertThat(parsed.content()).contains("class App");
    }

    @Test
    void validateSingleFileOutput_wrongPath_throws() {
        assertThatThrownBy(() -> validator.validateSingleFileOutput("""
                {"path":"other.java","content":"class X {}"}
                """, "src/App.java"))
                .isInstanceOf(ValidationHookException.class)
                .hasMessageContaining("does not match");
    }

    @Test
    void validateSingleFileOutput_placeholderContent_throws() {
        assertThatThrownBy(() -> validator.validateSingleFileOutput("""
                {"path":"src/App.java","content":"// TODO implement"}
                """, "src/App.java"))
                .isInstanceOf(ValidationHookException.class)
                .hasMessageContaining("placeholder");
    }
}

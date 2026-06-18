package com.midas.d3.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.midas.d3.config.JacksonConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FeatureManifestValidatorTest {

    private FeatureManifestValidator validator;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        validator = new FeatureManifestValidator();
        objectMapper = new JacksonConfig().objectMapper();
    }

    @Test
    void validateManifestArray_validEntries_collectsNoViolations() throws Exception {
        JsonNode manifest = objectMapper.readTree("""
                [
                  {
                    "feature_id": "create-task",
                    "feature_name": "Create task",
                    "files": ["src/task.js"],
                    "entry_points": ["createTask"]
                  }
                ]
                """);
        var violations = new java.util.ArrayList<String>();

        validator.validateManifestArray(manifest, violations);

        assertThat(violations).isEmpty();
    }

    @Test
    void validateManifestArray_missingFeatureId_addsViolation() throws Exception {
        JsonNode manifest = objectMapper.readTree("""
                [
                  {
                    "feature_name": "Create task",
                    "files": ["src/task.js"],
                    "entry_points": ["createTask"]
                  }
                ]
                """);
        var violations = new java.util.ArrayList<String>();

        validator.validateManifestArray(manifest, violations);

        assertThat(violations).anyMatch(v -> v.contains("feature_id"));
    }

    @Test
    void validateManifestArray_duplicateFeatureId_addsViolation() throws Exception {
        JsonNode manifest = objectMapper.readTree("""
                [
                  {
                    "feature_id": "create-task",
                    "feature_name": "Create task",
                    "files": ["src/task.js"],
                    "entry_points": ["createTask"]
                  },
                  {
                    "feature_id": "create-task",
                    "feature_name": "Create task again",
                    "files": ["src/task2.js"],
                    "entry_points": ["createTask2"]
                  }
                ]
                """);
        var violations = new java.util.ArrayList<String>();

        validator.validateManifestArray(manifest, violations);

        assertThat(violations).anyMatch(v -> v.contains("duplicated"));
    }

    @Test
    void validateManifestArray_emptyFilesArray_addsViolation() throws Exception {
        JsonNode manifest = objectMapper.readTree("""
                [
                  {
                    "feature_id": "create-task",
                    "feature_name": "Create task",
                    "files": [],
                    "entry_points": ["createTask"]
                  }
                ]
                """);
        var violations = new java.util.ArrayList<String>();

        validator.validateManifestArray(manifest, violations);

        assertThat(violations).anyMatch(v -> v.contains(".files"));
    }

    @Test
    void validateManifestArray_emptyEntryPointsArray_addsViolation() throws Exception {
        JsonNode manifest = objectMapper.readTree("""
                [
                  {
                    "feature_id": "create-task",
                    "feature_name": "Create task",
                    "files": ["src/task.js"],
                    "entry_points": []
                  }
                ]
                """);
        var violations = new java.util.ArrayList<String>();

        validator.validateManifestArray(manifest, violations);

        assertThat(violations).anyMatch(v -> v.contains(".entry_points"));
    }

    @Test
    void validateManifestArray_notArray_addsViolation() throws Exception {
        JsonNode manifest = objectMapper.readTree("""
                {"feature_id":"create-task"}
                """);
        var violations = new java.util.ArrayList<String>();

        validator.validateManifestArray(manifest, violations);

        assertThat(violations).anyMatch(v -> v.contains("must be an array"));
    }
}

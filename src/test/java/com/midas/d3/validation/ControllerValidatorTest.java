package com.midas.d3.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.midas.d3.config.JacksonConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link ControllerValidator} — the Phase 3 Product-Owner quality-gate
 * schema validator. These pin the verdict / coverage_matrix / remediation_block contract.
 */
@DisplayName("ControllerValidator — Product-Owner gate schema")
class ControllerValidatorTest {

    private ControllerValidator validator;

    @BeforeEach
    void setUp() {
        validator = new ControllerValidator(new JacksonConfig().objectMapper());
    }

    // ── Happy paths ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Accepts well-formed verdict reports")
    class HappyPaths {

        @Test
        @DisplayName("PASS verdict with empty remediation is valid")
        void pass_isValid() {
            String json = """
                {
                  "verdict": "PASS",
                  "summary": "Everything requested was delivered.",
                  "coverage_matrix": [
                    {"requested_feature": "Create task", "status": "COVERED", "evidence": "create-task in TaskController.java"}
                  ],
                  "remediation_block": {"required_changes": [], "recommendations": []}
                }
                """;
            JsonNode node = validator.validate(json);
            assertThat(node.get("verdict").asText()).isEqualTo("PASS");
        }

        @Test
        @DisplayName("PASS_WITH_NOTES verdict is valid")
        void passWithNotes_isValid() {
            String json = """
                {
                  "verdict": "PASS_WITH_NOTES",
                  "summary": "Met intent with minor notes.",
                  "coverage_matrix": [
                    {"requested_feature": "Delete task", "status": "PARTIAL", "evidence": "delete-task partial in Task.java"}
                  ],
                  "remediation_block": {"required_changes": [], "recommendations": ["Add hard delete"]}
                }
                """;
            assertThatNoException().isThrownBy(() -> validator.validate(json));
        }

        @Test
        @DisplayName("REJECT verdict with actionable required_changes is valid (a well-formed rejection)")
        void reject_withRequiredChanges_isValid() {
            String json = """
                {
                  "verdict": "REJECT",
                  "summary": "Assignment feature missing.",
                  "coverage_matrix": [
                    {"requested_feature": "Assign task", "status": "MISSING", "evidence": "assign-task not in TaskController.java"}
                  ],
                  "remediation_block": {"required_changes": ["Implement task assignment"], "recommendations": []}
                }
                """;
            JsonNode node = validator.validate(json);
            assertThat(node.get("verdict").asText()).isEqualTo("REJECT");
        }

        @Test
        @DisplayName("verdict is case-insensitive")
        void verdict_caseInsensitive() {
            String json = """
                {
                  "verdict": "pass",
                  "coverage_matrix": [{"requested_feature": "F", "status": "covered", "evidence": "feature-a covered"}],
                  "remediation_block": {"required_changes": []}
                }
                """;
            assertThatNoException().isThrownBy(() -> validator.validate(json));
        }
    }

    // ── Verdict violations ────────────────────────────────────────────────────

    @Nested
    @DisplayName("Rejects bad verdicts")
    class VerdictViolations {

        @Test
        @DisplayName("missing verdict is rejected")
        void missingVerdict() {
            String json = """
                {
                  "coverage_matrix": [{"requested_feature": "F", "status": "COVERED", "evidence": "feature-a covered"}],
                  "remediation_block": {"required_changes": []}
                }
                """;
            assertThatThrownBy(() -> validator.validate(json))
                    .isInstanceOf(ValidationHookException.class)
                    .hasMessageContaining("verdict");
        }

        @Test
        @DisplayName("unknown verdict value is rejected")
        void unknownVerdict() {
            String json = """
                {
                  "verdict": "MAYBE",
                  "coverage_matrix": [{"requested_feature": "F", "status": "COVERED", "evidence": "feature-a covered"}],
                  "remediation_block": {"required_changes": []}
                }
                """;
            assertThatThrownBy(() -> validator.validate(json))
                    .isInstanceOf(ValidationHookException.class)
                    .hasMessageContaining("verdict");
        }

        @Test
        @DisplayName("REJECT without required_changes is rejected (rejection must be actionable)")
        void rejectWithoutRemediation() {
            String json = """
                {
                  "verdict": "REJECT",
                  "coverage_matrix": [{"requested_feature": "F", "status": "MISSING", "evidence": "feature-a missing"}],
                  "remediation_block": {"required_changes": [], "recommendations": []}
                }
                """;
            assertThatThrownBy(() -> validator.validate(json))
                    .isInstanceOf(ValidationHookException.class)
                    .hasMessageContaining("required_changes");
        }
    }

    // ── Coverage matrix violations ────────────────────────────────────────────

    @Nested
    @DisplayName("Rejects bad coverage_matrix")
    class CoverageMatrixViolations {

        @Test
        @DisplayName("missing coverage_matrix is rejected")
        void missingMatrix() {
            String json = """
                {
                  "verdict": "PASS",
                  "remediation_block": {"required_changes": []}
                }
                """;
            assertThatThrownBy(() -> validator.validate(json))
                    .isInstanceOf(ValidationHookException.class)
                    .hasMessageContaining("coverage_matrix");
        }

        @Test
        @DisplayName("empty coverage_matrix is rejected")
        void emptyMatrix() {
            String json = """
                {
                  "verdict": "PASS",
                  "coverage_matrix": [],
                  "remediation_block": {"required_changes": []}
                }
                """;
            assertThatThrownBy(() -> validator.validate(json))
                    .isInstanceOf(ValidationHookException.class)
                    .hasMessageContaining("coverage_matrix");
        }

        @Test
        @DisplayName("coverage_matrix entry missing requested_feature is rejected")
        void entryMissingFeature() {
            String json = """
                {
                  "verdict": "PASS",
                  "coverage_matrix": [{"status": "COVERED"}],
                  "remediation_block": {"required_changes": []}
                }
                """;
            assertThatThrownBy(() -> validator.validate(json))
                    .isInstanceOf(ValidationHookException.class)
                    .hasMessageContaining("requested_feature");
        }

        @Test
        @DisplayName("coverage_matrix entry with invalid status is rejected")
        void entryInvalidStatus() {
            String json = """
                {
                  "verdict": "PASS",
                  "coverage_matrix": [{"requested_feature": "F", "status": "DONE", "evidence": "feature-a"}],
                  "remediation_block": {"required_changes": []}
                }
                """;
            assertThatThrownBy(() -> validator.validate(json))
                    .isInstanceOf(ValidationHookException.class)
                    .hasMessageContaining("status");
        }
    }

    // ── Remediation block + structural guards ─────────────────────────────────

    @Nested
    @DisplayName("Evidence cross-references featureManifest when present")
    class ManifestEvidenceRules {

        private static final String MANIFEST = """
                [
                  {
                    "feature_id": "create-task",
                    "feature_name": "Create task",
                    "files": ["src/main/java/com/example/TaskController.java"],
                    "entry_points": ["TaskController"]
                  },
                  {
                    "feature_id": "assign-task",
                    "feature_name": "Assign task",
                    "files": ["src/main/java/com/example/TaskController.java"],
                    "entry_points": ["TaskController"]
                  }
                ]
                """;

        @Test
        @DisplayName("evidence citing feature_id passes validateWithFeatureManifest")
        void evidenceWithFeatureId_passes() throws Exception {
            String json = """
                {
                  "verdict": "PASS",
                  "summary": "Covered.",
                  "coverage_matrix": [
                    {"requested_feature": "Create task", "status": "COVERED", "evidence": "create-task mapped in manifest"}
                  ],
                  "remediation_block": {"required_changes": [], "recommendations": []}
                }
                """;
            JsonNode manifest = new JacksonConfig().objectMapper().readTree(MANIFEST);
            assertThatNoException().isThrownBy(() -> validator.validateWithFeatureManifest(json, manifest));
        }

        @Test
        @DisplayName("evidence citing manifest file path passes validateWithFeatureManifest")
        void evidenceWithFilePath_passes() throws Exception {
            String json = """
                {
                  "verdict": "PASS",
                  "summary": "Covered.",
                  "coverage_matrix": [
                    {"requested_feature": "Create task", "status": "COVERED", "evidence": "src/main/java/com/example/TaskController.java implements create"}
                  ],
                  "remediation_block": {"required_changes": [], "recommendations": []}
                }
                """;
            JsonNode manifest = new JacksonConfig().objectMapper().readTree(MANIFEST);
            assertThatNoException().isThrownBy(() -> validator.validateWithFeatureManifest(json, manifest));
        }

        @Test
        @DisplayName("evidence not referencing manifest fails validateWithFeatureManifest")
        void evidenceWithoutManifestReference_fails() throws Exception {
            String json = """
                {
                  "verdict": "PASS",
                  "summary": "Covered.",
                  "coverage_matrix": [
                    {"requested_feature": "Create task", "status": "COVERED", "evidence": "implemented somewhere"}
                  ],
                  "remediation_block": {"required_changes": [], "recommendations": []}
                }
                """;
            JsonNode manifest = new JacksonConfig().objectMapper().readTree(MANIFEST);
            assertThatThrownBy(() -> validator.validateWithFeatureManifest(json, manifest))
                    .isInstanceOf(ValidationHookException.class)
                    .hasMessageContaining("featureManifest");
        }

        @Test
        @DisplayName("validate without manifest skips cross-reference")
        void validateWithoutManifest_skipsCrossReference() {
            String json = """
                {
                  "verdict": "PASS",
                  "summary": "Covered.",
                  "coverage_matrix": [
                    {"requested_feature": "Create task", "status": "COVERED", "evidence": "implemented somewhere"}
                  ],
                  "remediation_block": {"required_changes": [], "recommendations": []}
                }
                """;
            assertThatNoException().isThrownBy(() -> validator.validate(json));
        }
    }

    @Nested
    @DisplayName("Rejects bad remediation_block and malformed input")
    class StructuralViolations {

        @Test
        @DisplayName("missing remediation_block is rejected")
        void missingRemediation() {
            String json = """
                {
                  "verdict": "PASS",
                  "coverage_matrix": [{"requested_feature": "F", "status": "COVERED"}]
                }
                """;
            assertThatThrownBy(() -> validator.validate(json))
                    .isInstanceOf(ValidationHookException.class)
                    .hasMessageContaining("remediation_block");
        }

        @Test
        @DisplayName("blank input is rejected")
        void blankInput() {
            assertThatThrownBy(() -> validator.validate("   "))
                    .isInstanceOf(ValidationHookException.class);
        }

        @Test
        @DisplayName("non-object root is rejected")
        void nonObjectRoot() {
            assertThatThrownBy(() -> validator.validate("[1,2,3]"))
                    .isInstanceOf(ValidationHookException.class);
        }
    }
}

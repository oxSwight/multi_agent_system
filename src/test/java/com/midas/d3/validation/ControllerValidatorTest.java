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
                    {"requested_feature": "Create task", "status": "COVERED", "evidence": "implemented"}
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
                    {"requested_feature": "Delete task", "status": "PARTIAL", "evidence": "soft delete only"}
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
                    {"requested_feature": "Assign task", "status": "MISSING", "evidence": "no endpoint"}
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
                  "coverage_matrix": [{"requested_feature": "F", "status": "covered"}],
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
                  "coverage_matrix": [{"requested_feature": "F", "status": "COVERED"}],
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
                  "coverage_matrix": [{"requested_feature": "F", "status": "COVERED"}],
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
                  "coverage_matrix": [{"requested_feature": "F", "status": "MISSING"}],
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
                  "coverage_matrix": [{"requested_feature": "F", "status": "DONE"}],
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

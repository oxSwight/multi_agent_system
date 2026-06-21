package com.midas.d3.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.midas.d3.config.JacksonConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class GoalKeeperValidatorTest {

    private SystemAnalystValidator systemAnalystValidator;
    private SoftwareArchitectValidator architectValidator;
    private SecOpsEngineerValidator secOpsValidator;

    @BeforeEach
    void setUp() {
        var mapper = new JacksonConfig().objectMapper();
        systemAnalystValidator = new SystemAnalystValidator(mapper);
        architectValidator     = new SoftwareArchitectValidator(mapper);
        secOpsValidator        = new SecOpsEngineerValidator(mapper);
    }

    // ── SystemAnalystValidator ───────────────────────────────────────────────

    @Nested
    class SystemAnalystValidatorTests {

        @Test
        void validate_validJson_returnsNode() {
            String json = """
                {
                  "business_goal": "Build a todo app",
                  "runtime_environment": {
                    "execution_model": "CLIENT_SIDE",
                    "deployment_target": "BROWSER_EXTENSION",
                    "requires_backend": false,
                    "persistence": "BROWSER_STORAGE",
                    "forbidden_infrastructure": ["Docker", "PostgreSQL", "Spring Boot"],
                    "justification": "A page-local todo tool needs no server."
                  },
                  "core_features": ["Create task", "Delete task"],
                  "edge_cases_and_handling": [{"case": "empty input", "solution": "return 400"}],
                  "non_functional_requirements": ["< 200ms response time"]
                }
                """;
            JsonNode result = systemAnalystValidator.validate(json);
            assertThat(result.get("business_goal").asText()).isEqualTo("Build a todo app");
        }

        @Test
        void validate_missingRuntimeEnvironment_throwsValidationHookException() {
            String json = """
                {
                  "business_goal": "Build a todo app",
                  "core_features": ["Create task"],
                  "edge_cases_and_handling": [],
                  "non_functional_requirements": []
                }
                """;
            assertThatThrownBy(() -> systemAnalystValidator.validate(json))
                    .isInstanceOf(ValidationHookException.class)
                    .hasMessageContaining("runtime_environment");
        }

        @Test
        void validate_missingBusinessGoal_throwsValidationHookException() {
            String json = """
                {
                  "core_features": ["Create task"],
                  "edge_cases_and_handling": [],
                  "performance_constraints": []
                }
                """;
            assertThatThrownBy(() -> systemAnalystValidator.validate(json))
                    .isInstanceOf(ValidationHookException.class)
                    .hasMessageContaining("business_goal");
        }

        @Test
        void validate_emptyCoreFeaturesArray_throwsValidationHookException() {
            String json = """
                {
                  "business_goal": "Test",
                  "core_features": [],
                  "edge_cases_and_handling": [],
                  "performance_constraints": []
                }
                """;
            assertThatThrownBy(() -> systemAnalystValidator.validate(json))
                    .isInstanceOf(ValidationHookException.class)
                    .hasMessageContaining("core_features");
        }

        @Test
        void validate_nullInput_throwsValidationHookException() {
            assertThatThrownBy(() -> systemAnalystValidator.validate((String) null))
                    .isInstanceOf(ValidationHookException.class)
                    .hasMessageContaining("null or blank");
        }

        @Test
        void validate_blankInput_throwsValidationHookException() {
            assertThatThrownBy(() -> systemAnalystValidator.validate("   "))
                    .isInstanceOf(ValidationHookException.class);
        }

        @Test
        void validate_invalidJson_throwsValidationHookException() {
            assertThatThrownBy(() -> systemAnalystValidator.validate("{not valid json"))
                    .isInstanceOf(ValidationHookException.class)
                    .hasMessageContaining("JSON parse error");
        }

        @Test
        void validate_rootIsArray_throwsValidationHookException() {
            assertThatThrownBy(() -> systemAnalystValidator.validate("[\"a\",\"b\"]"))
                    .isInstanceOf(ValidationHookException.class)
                    .hasMessageContaining("JSON object");
        }

        @Test
        void validate_edgeCaseMissingSolution_throwsValidationHookException() {
            String json = """
                {
                  "business_goal": "Test",
                  "core_features": ["Feature 1"],
                  "edge_cases_and_handling": [{"case": "empty input"}],
                  "performance_constraints": []
                }
                """;
            assertThatThrownBy(() -> systemAnalystValidator.validate(json))
                    .isInstanceOf(ValidationHookException.class)
                    .hasMessageContaining("solution");
        }
    }

    // ── SoftwareArchitectValidator ───────────────────────────────────────────

    @Nested
    class SoftwareArchitectValidatorTests {

        @Test
        void validate_validClientOnlyArchitecture_returnsNode() {
            // A browser extension: NO database, NO REST endpoints — must pass.
            String json = """
                {
                  "has_external_integrations": false,
                  "architecture_style": "CLIENT_ONLY",
                  "tech_stack": {"language": "TypeScript", "framework": "none",
                                 "platform_apis": ["Manifest V3", "chrome.storage"], "build_tool": "none"},
                  "components": [
                    {"name": "popup", "type": "UI", "responsibility": "Render the task list"},
                    {"name": "manifest", "type": "MANIFEST", "responsibility": "Declare permissions"}
                  ],
                  "file_layout": ["manifest.json", "src/popup.ts", "src/storage.ts"],
                  "data_persistence": {"type": "BROWSER_STORAGE", "schema": []},
                  "api_contracts": []
                }
                """;
            JsonNode result = architectValidator.validate(json);
            assertThat(result.get("architecture_style").asText()).isEqualTo("CLIENT_ONLY");
        }

        @Test
        void validate_validServerArchitecture_returnsNode() {
            String json = """
                {
                  "has_external_integrations": true,
                  "architecture_style": "CLIENT_SERVER",
                  "tech_stack": {"language": "Java", "framework": "Spring Boot",
                                 "platform_apis": [], "build_tool": "Maven"},
                  "components": [{"name": "UserController", "type": "CONTROLLER", "responsibility": "Users API"}],
                  "file_layout": ["src/main/java/com/example/UserController.java"],
                  "data_persistence": {
                    "type": "RELATIONAL",
                    "schema": [{"table_name": "users", "columns": [{"name": "id", "type": "BIGINT"}]}]
                  },
                  "api_contracts": [
                    {"method": "GET", "path": "/api/users", "request_payload": {}, "expected_response": {}}
                  ]
                }
                """;
            JsonNode result = architectValidator.validate(json);
            assertThat(result.get("architecture_style").asText()).isEqualTo("CLIENT_SERVER");
        }

        @Test
        void validate_missingHasExternalIntegrations_throwsValidationHookException() {
            String json = """
                {
                  "architecture_style": "CLIENT_ONLY",
                  "tech_stack": {"language": "TypeScript", "framework": "none",
                                 "platform_apis": [], "build_tool": "none"},
                  "components": [{"name": "popup", "type": "UI", "responsibility": "UI"}],
                  "file_layout": ["manifest.json"],
                  "data_persistence": {"type": "BROWSER_STORAGE", "schema": []},
                  "api_contracts": []
                }
                """;
            assertThatThrownBy(() -> architectValidator.validate(json))
                    .isInstanceOf(ValidationHookException.class)
                    .hasMessageContaining("has_external_integrations");
        }

        @Test
        void validate_nullHasExternalIntegrations_throwsValidationHookException() {
            String json = """
                {
                  "has_external_integrations": null,
                  "architecture_style": "CLIENT_ONLY",
                  "tech_stack": {"language": "TypeScript", "framework": "none",
                                 "platform_apis": [], "build_tool": "none"},
                  "components": [{"name": "popup", "type": "UI", "responsibility": "UI"}],
                  "file_layout": ["manifest.json"],
                  "data_persistence": {"type": "BROWSER_STORAGE", "schema": []},
                  "api_contracts": []
                }
                """;
            assertThatThrownBy(() -> architectValidator.validate(json))
                    .isInstanceOf(ValidationHookException.class)
                    .hasMessageContaining("has_external_integrations");
        }

        @Test
        void validate_nonBooleanHasExternalIntegrations_throwsValidationHookException() {
            String json = """
                {
                  "has_external_integrations": "false",
                  "architecture_style": "CLIENT_ONLY",
                  "tech_stack": {"language": "TypeScript", "framework": "none",
                                 "platform_apis": [], "build_tool": "none"},
                  "components": [{"name": "popup", "type": "UI", "responsibility": "UI"}],
                  "file_layout": ["manifest.json"],
                  "data_persistence": {"type": "BROWSER_STORAGE", "schema": []},
                  "api_contracts": []
                }
                """;
            assertThatThrownBy(() -> architectValidator.validate(json))
                    .isInstanceOf(ValidationHookException.class)
                    .hasMessageContaining("has_external_integrations");
        }

        @Test
        void validate_serverStyleMissingApiContracts_throwsValidationHookException() {
            String json = """
                {
                  "has_external_integrations": false,
                  "architecture_style": "MONOLITH",
                  "tech_stack": {"language": "Java", "framework": "Spring Boot",
                                 "platform_apis": [], "build_tool": "Maven"},
                  "components": [{"name": "App", "type": "SERVICE", "responsibility": "core"}],
                  "file_layout": ["src/main/java/com/example/App.java"],
                  "data_persistence": {"type": "NONE", "schema": []},
                  "api_contracts": []
                }
                """;
            assertThatThrownBy(() -> architectValidator.validate(json))
                    .isInstanceOf(ValidationHookException.class)
                    .hasMessageContaining("api_contracts");
        }

        @Test
        void validate_invalidHttpMethod_throwsValidationHookException() {
            String json = """
                {
                  "has_external_integrations": true,
                  "architecture_style": "CLIENT_SERVER",
                  "tech_stack": {"language": "Java", "framework": "Spring Boot",
                                 "platform_apis": [], "build_tool": "Maven"},
                  "components": [{"name": "C", "type": "CONTROLLER", "responsibility": "x"}],
                  "file_layout": ["src/main/java/com/example/C.java"],
                  "data_persistence": {"type": "NONE", "schema": []},
                  "api_contracts": [{"method": "FETCH", "path": "/x"}]
                }
                """;
            assertThatThrownBy(() -> architectValidator.validate(json))
                    .isInstanceOf(ValidationHookException.class)
                    .hasMessageContaining("method");
        }
    }

    // ── SecOpsEngineerValidator ──────────────────────────────────────────────

    @Nested
    class SecOpsEngineerValidatorTests {

        @Test
        void validate_extensionPackage_noDockerfileRequired() {
            // A browser extension must pass WITHOUT a Dockerfile.
            String json = """
                {
                  "security_audit_report": ["LOW: host_permissions scoped to api.example.com — OK"],
                  "deployment_model": "BROWSER_EXTENSION_PACKAGE",
                  "release_artifacts": {
                    "package.sh": "zip -r extension.zip manifest.json src/"
                  }
                }
                """;
            JsonNode result = secOpsValidator.validate(json);
            assertThat(result.get("deployment_model").asText()).isEqualTo("BROWSER_EXTENSION_PACKAGE");
        }

        @Test
        void validate_markdownExtensionPackage_noJsonRequired() {
            String markdown = """
                DEPLOYMENT_MODEL: BROWSER_EXTENSION_PACKAGE

                ## Security Audit
                - LOW: host_permissions scoped to required domains — OK
                - MEDIUM: avoid innerHTML in content scripts

                ## Release Artifacts
                ```sh package.sh
                #!/bin/bash
                zip -r extension.zip manifest.json src/
                ```
                """;
            JsonNode result = secOpsValidator.validate(markdown);
            assertThat(result.get("deployment_model").asText()).isEqualTo("BROWSER_EXTENSION_PACKAGE");
            assertThat(result.get("security_audit_report").size()).isGreaterThan(0);
            assertThat(result.get("release_artifacts").get("package.sh").asText())
                    .contains("extension.zip");
        }

        @Test
        void validate_containerizedWithoutDockerfile_throwsValidationHookException() {
            String json = """
                {
                  "security_audit_report": [],
                  "deployment_model": "CONTAINERIZED",
                  "release_artifacts": {"deploy.md": "kubectl apply -f k8s/"}
                }
                """;
            assertThatThrownBy(() -> secOpsValidator.validate(json))
                    .isInstanceOf(ValidationHookException.class)
                    .hasMessageContaining("Dockerfile");
        }

        @Test
        void validate_legacyTopLevelDockerfile_isAccepted() {
            String json = """
                {
                  "security_audit_report": ["No hardcoded credentials found."],
                  "Dockerfile": "FROM eclipse-temurin:21-jre\\nUSER app",
                  "docker-compose.yml": "version: '3.8'"
                }
                """;
            JsonNode result = secOpsValidator.validate(json);
            assertThat(result.get("Dockerfile").asText()).contains("FROM");
        }

        @Test
        void validate_noReleaseArtifacts_throwsValidationHookException() {
            String json = """
                {
                  "security_audit_report": ["INFO: nothing to deploy"],
                  "deployment_model": "STATIC_DEPLOY"
                }
                """;
            assertThatThrownBy(() -> secOpsValidator.validate(json))
                    .isInstanceOf(ValidationHookException.class)
                    .hasMessageContaining("release artifact");
        }

        @Test
        void validate_hybridWithBothSurfaces_passes() {
            String json = """
                {
                  "security_audit_report": [
                    "LOW: host_permissions scoped to api.example.com — OK",
                    "MEDIUM: JWT secret must be injected via env, not hardcoded"
                  ],
                  "deployment_model": "HYBRID",
                  "release_artifacts": {
                    "Dockerfile": "FROM eclipse-temurin:21-jre\\nUSER app\\nWORKDIR /app",
                    "docker-compose.yml": "version: '3.8'\\nservices:\\n  api:\\n    build: .",
                    "package.sh": "zip -r extension.zip manifest.json src/",
                    "manifest_summary": "permissions: storage; host_permissions: https://api.example.com/*"
                  }
                }
                """;
            JsonNode result = secOpsValidator.validate(json);
            assertThat(result.get("deployment_model").asText()).isEqualTo("HYBRID");
            assertThat(result.get("release_artifacts").get("Dockerfile").asText()).contains("FROM");
            assertThat(result.get("release_artifacts").get("manifest_summary").asText())
                    .contains("permissions");
        }

        @Test
        void validate_hybridMissingDockerfile_throwsValidationHookException() {
            String json = """
                {
                  "security_audit_report": ["LOW: manifest permissions OK"],
                  "deployment_model": "HYBRID",
                  "release_artifacts": {
                    "package.sh": "zip -r extension.zip manifest.json src/",
                    "manifest_summary": "permissions: storage only"
                  }
                }
                """;
            assertThatThrownBy(() -> secOpsValidator.validate(json))
                    .isInstanceOf(ValidationHookException.class)
                    .hasMessageContaining("Dockerfile");
        }

        @Test
        void validate_hybridMissingExtensionArtifacts_throwsValidationHookException() {
            String json = """
                {
                  "security_audit_report": ["MEDIUM: env-injected secrets required"],
                  "deployment_model": "HYBRID",
                  "release_artifacts": {
                    "Dockerfile": "FROM eclipse-temurin:21-jre\\nUSER app",
                    "docker-compose.yml": "version: '3.8'"
                  }
                }
                """;
            assertThatThrownBy(() -> secOpsValidator.validate(json))
                    .isInstanceOf(ValidationHookException.class)
                    .hasMessageContaining("client/extension release artifacts");
        }

        @Test
        void validate_hybridDockerfileWithoutFrom_throwsValidationHookException() {
            String json = """
                {
                  "security_audit_report": ["LOW: manifest permissions OK"],
                  "deployment_model": "HYBRID",
                  "release_artifacts": {
                    "Dockerfile": "USER app\\nWORKDIR /app",
                    "package.sh": "zip -r extension.zip manifest.json src/"
                  }
                }
                """;
            assertThatThrownBy(() -> secOpsValidator.validate(json))
                    .isInstanceOf(ValidationHookException.class)
                    .hasMessageContaining("FROM");
        }
    }

    // ── ValidationHookException ──────────────────────────────────────────────

    @Nested
    class ValidationHookExceptionTests {

        @Test
        void exception_carriesAgentAndStage() {
            var ex = new ValidationHookException("TestAgent", "TEST_STAGE", "violation 1");
            assertThat(ex.getAgentName()).isEqualTo("TestAgent");
            assertThat(ex.getStage()).isEqualTo("TEST_STAGE");
            assertThat(ex.getViolations()).containsExactly("violation 1");
        }

        @Test
        void exception_multipleViolations_allPresent() {
            var ex = new ValidationHookException("A", "S", java.util.List.of("v1", "v2", "v3"));
            assertThat(ex.getViolations()).hasSize(3);
            assertThat(ex.getMessage()).contains("v1").contains("v2").contains("v3");
        }

        @Test
        void exception_violationsList_isImmutable() {
            var ex = new ValidationHookException("A", "S", java.util.List.of("v1"));
            assertThatThrownBy(() -> ex.getViolations().add("v2"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }
}

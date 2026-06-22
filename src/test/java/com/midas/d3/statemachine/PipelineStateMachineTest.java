package com.midas.d3.statemachine;

import com.midas.d3.context.MidasContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.statemachine.StateMachine;
import org.springframework.statemachine.config.StateMachineFactory;
import org.springframework.test.context.ActiveProfiles;
import reactor.core.publisher.Mono;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for the MIDAS_D3 State Machine.
 *
 * <p>Tests use a real Spring context to validate the full guard + action chain.
 * Each test creates its own machine instance to ensure isolation.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("MIDAS State Machine Tests")
class PipelineStateMachineTest {

    @Autowired
    private StateMachineFactory<MidasState, MidasEvent> factory;

    private StateMachine<MidasState, MidasEvent> machine;

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private static final String VALID_TECH_SPEC = """
            {
              "business_goal": "Build a task management system",
              "runtime_environment": {
                "execution_model": "SERVER_SIDE",
                "deployment_target": "CLOUD_SERVICE",
                "requires_backend": true,
                "persistence": "CLOUD_DB",
                "forbidden_infrastructure": [],
                "justification": "Multi-user shared state requires a hosted backend."
              },
              "core_features": ["Create task", "Assign task", "Track progress"],
              "edge_cases_and_handling": [
                {"case": "empty input", "solution": "return HTTP 400"},
                {"case": "duplicate task", "solution": "return HTTP 409"}
              ],
              "non_functional_requirements": ["< 200ms p95 latency"]
            }
            """;

    private static final String VALID_CODE_GEN = """
            {
              "source_files": {
                "src/main/java/com/example/TaskController.java": "public class TaskController { }"
              },
              "feature_manifest": [
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
                },
                {
                  "feature_id": "track-progress",
                  "feature_name": "Track progress",
                  "files": ["src/main/java/com/example/TaskController.java"],
                  "entry_points": ["TaskController"]
                }
              ]
            }
            """;

    private static final String VALID_CODE_GEN_REMEDIATED = """
            {
              "source_files": {
                "src/main/java/com/example/TaskController.java": "public class TaskController { void assign() {} }"
              },
              "feature_manifest": [
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
                  "entry_points": ["TaskController.assign"]
                },
                {
                  "feature_id": "track-progress",
                  "feature_name": "Track progress",
                  "files": ["src/main/java/com/example/TaskController.java"],
                  "entry_points": ["TaskController"]
                }
              ]
            }
            """;

    private static final String INVALID_JSON = "{ broken json";

    private static final String CONTROLLER_PASS = """
            {
              "verdict": "PASS",
              "summary": "All requested features are covered by the delivered solution.",
              "coverage_matrix": [
                {"requested_feature": "Create task", "status": "COVERED", "evidence": "create-task in src/main/java/com/example/TaskController.java"}
              ],
              "remediation_block": {"required_changes": [], "recommendations": []}
            }
            """;

    private static final String CONTROLLER_REJECT = """
            {
              "verdict": "REJECT",
              "summary": "The assignment feature requested by the user was never implemented.",
              "coverage_matrix": [
                {"requested_feature": "Assign task", "status": "MISSING", "evidence": "assign-task absent from src/main/java/com/example/TaskController.java"}
              ],
              "remediation_block": {
                "required_changes": ["Implement task assignment end-to-end (API + persistence + UI)"],
                "recommendations": []
              }
            }
            """;

    private static final String INVALID_PATCH_WITH_PLACEHOLDER = """
            {
              "source_files": {
                "src/main/java/com/example/TaskController.java": "public class TaskController { // TODO implement assign }"
              },
              "feature_manifest": [
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
                },
                {
                  "feature_id": "track-progress",
                  "feature_name": "Track progress",
                  "files": ["src/main/java/com/example/TaskController.java"],
                  "entry_points": ["TaskController"]
                }
              ]
            }
            """;

    private static final String MISSING_REQUIRED_FIELD = """
            {
              "core_features": ["Feature A"],
              "edge_cases_and_handling": [],
              "performance_constraints": []
            }
            """;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @BeforeEach
    void setUp() {
        machine = factory.getStateMachine(UUID.randomUUID().toString());
        machine.startReactively().block();
    }

    @AfterEach
    void tearDown() {
        if (machine != null) {
            machine.stopReactively().block();
        }
    }

    // ── Initial State ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("Machine starts in IDLE state")
    void machineStartsInIdle() {
        assertThat(machine.getState().getId()).isEqualTo(MidasState.IDLE);
    }

    // ── START event ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("START event transitions IDLE → SYSTEM_ANALYSIS and creates MidasContext")
    void startEvent_movesToSystemAnalysis() {
        sendStart("Build a todo app");

        assertThat(machine.getState().getId()).isEqualTo(MidasState.SYSTEM_ANALYSIS);

        MidasContext ctx = extractContext();
        assertThat(ctx).isNotNull();
        assertThat(ctx.getRawUserIdea()).isEqualTo("Build a todo app");
        assertThat(ctx.getValidationRetries()).isZero();
        assertThat(ctx.safeAuditLog()).isNotEmpty();
    }

    @Test
    @DisplayName("START event with leading/trailing whitespace — idea is stripped")
    void startEvent_stripsWhitespace() {
        sendStart("  Build a todo app  ");
        MidasContext ctx = extractContext();
        assertThat(ctx.getRawUserIdea()).isEqualTo("Build a todo app");
    }

    // ── SUBMIT_RESULT — validation pass ───────────────────────────────────────

    @Test
    @DisplayName("Valid SUBMIT_RESULT at SYSTEM_ANALYSIS → advances to ARCHITECTURE_DESIGN")
    void submitResult_validJson_advancesToNextStage() {
        sendStart("Build a todo app");
        assertThat(machine.getState().getId()).isEqualTo(MidasState.SYSTEM_ANALYSIS);

        sendSubmit(VALID_TECH_SPEC);

        assertThat(machine.getState().getId()).isEqualTo(MidasState.ARCHITECTURE_DESIGN);

        MidasContext ctx = extractContext();
        assertThat(ctx.getTechnicalSpec()).isNotNull();
        assertThat(ctx.getTechnicalSpec().get("business_goal").asText())
                .isEqualTo("Build a task management system");
        assertThat(ctx.getValidationRetries()).isZero();
    }

    // ── SUBMIT_RESULT — retry logic ───────────────────────────────────────────

    @Test
    @DisplayName("First invalid SUBMIT_RESULT increments retry counter; machine stays in same state")
    void submitResult_invalidJson_firstRetry_staysInStage() {
        sendStart("Build a todo app");

        sendSubmit(INVALID_JSON);

        assertThat(machine.getState().getId()).isEqualTo(MidasState.SYSTEM_ANALYSIS);
        MidasContext ctx = extractContext();
        assertThat(ctx.getValidationRetries()).isEqualTo(1);
        assertThat(ctx.safeAuditLog())
                .anyMatch(e -> e.getSeverity() == com.midas.d3.context.AuditEntry.Severity.WARN);
    }

    @Test
    @DisplayName("Second invalid SUBMIT_RESULT → retry=2; machine still in same state")
    void submitResult_invalidJson_secondRetry_staysInStage() {
        // Submit 1 (retries=0): RetryGuard: 0+1=1 < 3 → fires → retries=1, stays
        // Submit 2 (retries=1): RetryGuard: 1+1=2 < 3 → fires → retries=2, stays
        sendStart("Build a todo app");
        sendSubmit(INVALID_JSON);
        sendSubmit(MISSING_REQUIRED_FIELD);

        assertThat(machine.getState().getId()).isEqualTo(MidasState.SYSTEM_ANALYSIS);
        assertThat(extractContext().getValidationRetries()).isEqualTo(2);
    }

    @Test
    @DisplayName("Third invalid SUBMIT_RESULT (retries exhausted) → transitions to ERROR")
    void submitResult_retriesExhausted_movesToError() {
        // With maxRetries=3: RetriesExhaustedGuard checks retries+1>=3
        // Submit 1 (retries=0): 0+1=1>=3? No → last branch, retries→1
        // Submit 2 (retries=1): 1+1=2>=3? No → last branch, retries→2
        // Submit 3 (retries=2): 2+1=3>=3? Yes → then branch → ERROR
        sendStart("Build a todo app");
        sendSubmit(INVALID_JSON);             // retries → 1
        sendSubmit(MISSING_REQUIRED_FIELD);   // retries → 2
        sendSubmit(INVALID_JSON);             // exhausted → ERROR

        assertThat(machine.getState().getId()).isEqualTo(MidasState.ERROR);

        MidasContext ctx = extractContext();
        assertThat(ctx.getLastErrorMessage()).isNotBlank();
        assertThat(ctx.safeAuditLog())
                .anyMatch(e -> e.getSeverity() == com.midas.d3.context.AuditEntry.Severity.ERROR);
    }

    @Test
    @DisplayName("Valid result after failed retries advances past retry state")
    void submitResult_validAfterOneRetry_advances() {
        sendStart("Build a todo app");
        sendSubmit(INVALID_JSON);         // retry 1 (retries=1)
        sendSubmit(VALID_TECH_SPEC);      // validation passes

        assertThat(machine.getState().getId()).isEqualTo(MidasState.ARCHITECTURE_DESIGN);
        assertThat(extractContext().getValidationRetries()).isZero();
    }

    // ── RESET ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("RESET from ERROR → transitions back to IDLE")
    void reset_fromError_returnsToIdle() {
        sendStart("Build a todo app");
        sendSubmit(INVALID_JSON);
        sendSubmit(INVALID_JSON);
        sendSubmit(INVALID_JSON);
        assertThat(machine.getState().getId()).isEqualTo(MidasState.ERROR);

        machine.sendEvent(Mono.just(
                MessageBuilder.withPayload(MidasEvent.RESET).build())).blockLast();

        assertThat(machine.getState().getId()).isEqualTo(MidasState.IDLE);
    }

    // ── Guard edge cases ──────────────────────────────────────────────────────

    @Test
    @DisplayName("SUBMIT_RESULT with null llmOutput header stays in stage (guard returns false)")
    void submitResult_nullLlmOutput_staysInStage() {
        sendStart("Build a todo app");

        machine.sendEvent(Mono.just(
                MessageBuilder.withPayload(MidasEvent.SUBMIT_RESULT).build()
        )).blockLast();

        assertThat(machine.getState().getId()).isEqualTo(MidasState.SYSTEM_ANALYSIS);
        assertThat(extractContext().getValidationRetries()).isEqualTo(1);
    }

    @Test
    @DisplayName("SUBMIT_RESULT in IDLE state is ignored (no matching transition)")
    void submitResult_inIdleState_isIgnored() {
        assertThat(machine.getState().getId()).isEqualTo(MidasState.IDLE);
        sendSubmit(VALID_TECH_SPEC);
        assertThat(machine.getState().getId()).isEqualTo(MidasState.IDLE);
    }

    // ── Full happy path ───────────────────────────────────────────────────────

    @Test
    @DisplayName("Happy path: START + 7 valid submissions + PASS gate → COMPLETED with all artifacts stored")
    void happyPath_fullPipeline_completesWithAllArtifacts() {
        sendStart("Build a task management system");
        assertState(MidasState.SYSTEM_ANALYSIS);

        // Stage 1
        sendSubmit(VALID_TECH_SPEC);
        assertState(MidasState.ARCHITECTURE_DESIGN);

        // Stage 2
        sendSubmit("""
            {
              "has_external_integrations": true,
              "architecture_style": "CLIENT_SERVER",
              "tech_stack": {"language": "Java", "framework": "Spring Boot",
                             "platform_apis": [], "build_tool": "Maven"},
              "components": [{"name": "TaskController", "type": "CONTROLLER", "responsibility": "Tasks API"}],
              "file_layout": ["src/main/java/com/example/TaskController.java"],
              "data_persistence": {
                "type": "RELATIONAL",
                "schema": [{"table_name": "tasks", "columns": [{"name": "id", "type": "BIGINT", "is_primary": true, "is_nullable": false}]}]
              },
              "api_contracts": [{"method": "GET", "path": "/api/tasks", "request_payload": {}, "expected_response": {}}]
            }
            """);
        assertState(MidasState.INTEGRATION_STRATEGY);

        // Stage 3
        sendSubmit("""
            {
              "has_external_integrations": false,
              "external_services": [],
              "client_side_constraints": ["No external APIs required"]
            }
            """);
        assertState(MidasState.CODE_GENERATION);

        sendSubmit(VALID_CODE_GEN);
        assertState(MidasState.TEST_GENERATION);

        MidasContext afterCodeGen = extractContext();
        assertThat(afterCodeGen.getGeneratedSourceCode()).isNotNull();
        assertThat(afterCodeGen.getFeatureManifest()).isNotNull();
        assertThat(afterCodeGen.getGeneratedSourceCode().has("src/main/java/com/example/TaskController.java")).isTrue();
        assertThat(afterCodeGen.getFeatureManifest()).hasSize(3);

        sendSubmit("""
            {
              "src/test/java/com/example/TaskControllerTest.java": "class TaskControllerTest { @Test void test() {} }"
            }
            """);
        assertState(MidasState.SECOPS_AUDIT);

        // Stage 6
        sendSubmit("""
            {
              "security_audit_report": ["No hardcoded credentials found."],
              "Dockerfile": "FROM eclipse-temurin:21-jre\\nCOPY app.jar app.jar\\nENTRYPOINT [\\"java\\",\\"-jar\\",\\"app.jar\\"]",
              "docker-compose.yml": "version: '3.8'\\nservices:\\n  app:\\n    build: ."
            }
            """);
        assertState(MidasState.PRODUCT_REVIEW);

        // Stage 7 — Controller / Product-Owner gate: PASS → COMPLETED
        sendSubmit(CONTROLLER_PASS);
        assertState(MidasState.COMPLETED);

        MidasContext ctx = extractContext();
        assertThat(ctx.getTechnicalSpec()).isNotNull();
        assertThat(ctx.getArchitectureDesign()).isNotNull();
        assertThat(ctx.getIntegrationStrategy()).isNotNull();
        assertThat(ctx.getGeneratedSourceCode()).isNotNull();
        assertThat(ctx.getFeatureManifest()).isNotNull();
        assertThat(ctx.getGeneratedTests()).isNotNull();
        assertThat(ctx.getSecOpsArtifacts()).isNotNull();
        assertThat(ctx.getProductReviewReport()).isNotNull();
        assertThat(ctx.getProductReviewReport().get("verdict").asText()).isEqualTo("PASS");
        assertThat(ctx.getValidationRetries()).isZero();
        assertThat(ctx.safeAuditLog()).hasSizeGreaterThanOrEqualTo(8);
    }

    @Test
    @DisplayName("Dynamic routing: architecture with has_external_integrations=false skips INTEGRATION_STRATEGY")
    void architectureReview_noExternalIntegrations_skipsIntegrationStage() {
        sendStart("Build a self-contained CLI tool");
        sendSubmit(VALID_TECH_SPEC);
        assertState(MidasState.ARCHITECTURE_DESIGN);

        sendSubmit("""
            {
              "architecture_style": "CLI_TOOL",
              "has_external_integrations": false,
              "tech_stack": {"language": "Java", "framework": "none", "platform_apis": [], "build_tool": "Maven"},
              "components": [{"name": "Main", "type": "SERVICE", "responsibility": "CLI entry point"}],
              "file_layout": ["src/main/java/com/example/Main.java"],
              "data_persistence": {"type": "LOCAL_FILE", "schema": []},
              "api_contracts": []
            }
            """);

        assertState(MidasState.CODE_GENERATION);
        MidasContext ctx = extractContext();
        assertThat(ctx.getArchitectureDesign()).isNotNull();
        assertThat(ctx.getIntegrationStrategy()).isNull();
    }

    @Test
    @DisplayName("Dynamic routing: architecture with has_external_integrations=true routes to INTEGRATION_STRATEGY")
    void architectureReview_withExternalIntegrations_routesToIntegrationStage() {
        sendStart("Build a task management system");
        sendSubmit(VALID_TECH_SPEC);
        assertState(MidasState.ARCHITECTURE_DESIGN);

        sendSubmit("""
            {
              "has_external_integrations": true,
              "architecture_style": "CLIENT_SERVER",
              "tech_stack": {"language": "Java", "framework": "Spring Boot",
                             "platform_apis": [], "build_tool": "Maven"},
              "components": [{"name": "TaskController", "type": "CONTROLLER", "responsibility": "Tasks API"}],
              "file_layout": ["src/main/java/com/example/TaskController.java"],
              "data_persistence": {
                "type": "RELATIONAL",
                "schema": [{"table_name": "tasks", "columns": [{"name": "id", "type": "BIGINT", "is_primary": true, "is_nullable": false}]}]
              },
              "api_contracts": [{"method": "GET", "path": "/api/tasks", "request_payload": {}, "expected_response": {}}]
            }
            """);

        assertState(MidasState.INTEGRATION_STRATEGY);
    }

    @Test
    @DisplayName("ARCHITECTURE_DESIGN missing has_external_integrations stays in stage and increments retry")
    void architectureReview_missingIntegrationFlag_retries() {
        sendStart("Build a task management system");
        sendSubmit(VALID_TECH_SPEC);
        assertState(MidasState.ARCHITECTURE_DESIGN);

        sendSubmit("""
            {
              "architecture_style": "CLIENT_SERVER",
              "tech_stack": {"language": "Java", "framework": "Spring Boot",
                             "platform_apis": [], "build_tool": "Maven"},
              "components": [{"name": "TaskController", "type": "CONTROLLER", "responsibility": "Tasks API"}],
              "file_layout": ["src/main/java/com/example/TaskController.java"],
              "data_persistence": {
                "type": "RELATIONAL",
                "schema": [{"table_name": "tasks", "columns": [{"name": "id", "type": "BIGINT", "is_primary": true, "is_nullable": false}]}]
              },
              "api_contracts": [{"method": "GET", "path": "/api/tasks", "request_payload": {}, "expected_response": {}}]
            }
            """);

        assertState(MidasState.ARCHITECTURE_DESIGN);
        assertThat(extractContext().getValidationRetries()).isEqualTo(1);
    }

    // ── Phase 3: Product-Owner quality gate (blocking) ────────────────────────

    @Test
    @DisplayName("PRODUCT_REVIEW with PASS_WITH_NOTES verdict → COMPLETED and report stored")
    void productReview_passWithNotes_completes() {
        drivePipelineToProductReview();

        sendSubmit("""
            {
              "verdict": "PASS_WITH_NOTES",
              "summary": "Intent met; minor polish suggested.",
              "coverage_matrix": [
                {"requested_feature": "Create task", "status": "COVERED", "evidence": "create-task in src/main/java/com/example/TaskController.java"}
              ],
              "remediation_block": {"required_changes": [], "recommendations": ["Add input debounce"]}
            }
            """);

        assertState(MidasState.COMPLETED);
        MidasContext ctx = extractContext();
        assertThat(ctx.getProductReviewReport()).isNotNull();
        assertThat(ctx.getProductReviewReport().get("verdict").asText()).isEqualTo("PASS_WITH_NOTES");
    }

    @Test
    @DisplayName("PRODUCT_REVIEW repairs invalid coverage_matrix evidence → COMPLETED")
    void productReview_invalidEvidenceFallback_completes() {
        drivePipelineToProductReview();

        sendSubmit("""
            {
              "verdict": "PASS",
              "summary": "Intent met.",
              "coverage_matrix": [
                {"requested_feature": "Create task", "status": "COVERED", "evidence": "implemented somewhere without manifest refs"},
                {"requested_feature": "Assign task", "status": "COVERED", "evidence": ""}
              ],
              "remediation_block": {"required_changes": [], "recommendations": []}
            }
            """);

        assertState(MidasState.COMPLETED);
        MidasContext ctx = extractContext();
        assertThat(ctx.getProductReviewReport()).isNotNull();
        assertThat(ctx.getProductReviewReport().get("coverage_matrix").get(0).get("evidence").asText())
                .contains("create-task");
    }

    @Test
    void productReview_reject_initiatesRemediationLoop() {
        drivePipelineToProductReview();

        sendSubmit(CONTROLLER_REJECT);

        assertState(MidasState.CODE_GENERATION);
        MidasContext ctx = extractContext();
        assertThat(ctx.getProductReviewRemediationAttempts()).isEqualTo(1);
        assertThat(ctx.getRemediationDirectiveOpt()).isPresent();
        assertThat(ctx.getRemediationDirective().get("source_verdict").asText()).isEqualTo("REJECT");
        assertThat(ctx.getRemediationDirective().get("required_changes").isArray()).isTrue();
        assertThat(ctx.getRemediationDirective().get("required_changes")).isNotEmpty();
        assertThat(ctx.getRemediationDirective().get("remediation_mode").asText()).isEqualTo("SURGICAL_PATCH");
        assertThat(ctx.getRemediationDirective().get("affected_paths").isArray()).isTrue();
        assertThat(ctx.getRemediationDirective().get("affected_paths")).isNotEmpty();
        assertThat(ctx.getRemediationDirective().get("affected_features").isArray()).isTrue();
        assertThat(ctx.getRemediationDirective().get("affected_features")).isNotEmpty();
        assertThat(ctx.getGeneratedSourceCode()).isNotNull();
        assertThat(ctx.getGeneratedTests()).isNotNull();
        assertThat(ctx.getFeatureManifest()).isNotNull();
        assertThat(ctx.getSecOpsArtifacts()).isNull();
        assertThat(ctx.getTechnicalSpec()).isNotNull();
        assertThat(ctx.getArchitectureDesign()).isNotNull();
        assertThat(ctx.getValidationRetries()).isZero();
        assertThat(ctx.safeAuditLog())
                .anyMatch(e -> e.getSeverity() == com.midas.d3.context.AuditEntry.Severity.WARN);
    }

    @Test
    @DisplayName("PRODUCT_REVIEW with second REJECT after remediation → ERROR with report attached")
    void productReview_rejectExhausted_routesToErrorWithReport() {
        drivePipelineToProductReview();
        sendSubmit(CONTROLLER_REJECT);
        assertState(MidasState.CODE_GENERATION);

        drivePipelineFromCodeGeneration();
        sendSubmit(CONTROLLER_REJECT);

        assertState(MidasState.ERROR);
        MidasContext ctx = extractContext();
        assertThat(ctx.getProductReviewReport()).isNotNull();
        assertThat(ctx.getProductReviewReport().get("verdict").asText()).isEqualTo("REJECT");
        assertThat(ctx.getProductReviewRemediationAttempts()).isEqualTo(1);
        assertThat(ctx.getLastErrorMessage())
                .contains("REJECTED")
                .contains("Implement task assignment end-to-end (API + persistence + UI)");
        assertThat(ctx.safeAuditLog())
                .anyMatch(e -> e.getSeverity() == com.midas.d3.context.AuditEntry.Severity.ERROR);
    }

    @Test
    @DisplayName("E2E: REJECT → SURGICAL_PATCH remediation → PASS (golden path)")
    void e2e_reject_surgicalPatch_pass() {
        drivePipelineToProductReview();
        sendSubmit(CONTROLLER_REJECT);
        assertState(MidasState.CODE_GENERATION);

        MidasContext afterReject = extractContext();
        assertThat(afterReject.getRemediationDirective().get("remediation_mode").asText())
                .isEqualTo("SURGICAL_PATCH");
        assertThat(afterReject.getSecOpsArtifacts()).isNull();

        drivePipelineFromCodeGeneration();
        sendSubmit(CONTROLLER_PASS);

        assertState(MidasState.COMPLETED);
        MidasContext ctx = extractContext();
        assertThat(ctx.getProductReviewRemediationAttempts()).isEqualTo(1);
        assertThat(ctx.getRemediationDirective().get("remediation_mode").asText()).isEqualTo("SURGICAL_PATCH");
        assertThat(ctx.getProductReviewReport().get("verdict").asText()).isEqualTo("PASS");
        assertThat(ctx.getGeneratedSourceCode().get("src/main/java/com/example/TaskController.java").asText())
                .contains("assign");
        assertThat(ctx.getFeatureManifest()).hasSize(3);
        assertThat(ctx.getGeneratedTests()).isNotNull();
        assertThat(ctx.getSecOpsArtifacts()).isNotNull();
    }

    @Test
    @DisplayName("E2E: REJECT → SURGICAL_PATCH patch validation fails → full regen envelope → PASS")
    void e2e_reject_surgicalPatchFails_fullRegenFallback_pass() {
        drivePipelineToProductReview();
        sendSubmit(CONTROLLER_REJECT);
        assertState(MidasState.CODE_GENERATION);
        assertThat(extractContext().getRemediationDirective().get("remediation_mode").asText())
                .isEqualTo("SURGICAL_PATCH");

        sendSubmit(INVALID_PATCH_WITH_PLACEHOLDER);
        assertState(MidasState.CODE_GENERATION);
        assertThat(extractContext().getValidationRetries()).isEqualTo(1);

        drivePipelineFromCodeGeneration();
        sendSubmit(CONTROLLER_PASS);

        assertState(MidasState.COMPLETED);
        MidasContext ctx = extractContext();
        assertThat(ctx.getProductReviewRemediationAttempts()).isEqualTo(1);
        assertThat(ctx.getProductReviewReport().get("verdict").asText()).isEqualTo("PASS");
        assertThat(ctx.getGeneratedSourceCode().get("src/main/java/com/example/TaskController.java").asText())
                .contains("assign");
        assertThat(ctx.getFeatureManifest()).hasSize(3);
        assertThat(ctx.getValidationRetries()).isZero();
    }

    @Test
    @DisplayName("E2E: REJECT → SURGICAL_PATCH → second REJECT → ERROR (remediation exhausted)")
    void e2e_reject_surgicalPatch_secondReject_error() {
        drivePipelineToProductReview();
        sendSubmit(CONTROLLER_REJECT);
        assertState(MidasState.CODE_GENERATION);
        assertThat(extractContext().getRemediationDirective().get("remediation_mode").asText())
                .isEqualTo("SURGICAL_PATCH");

        drivePipelineFromCodeGeneration();
        sendSubmit(CONTROLLER_REJECT);

        assertState(MidasState.ERROR);
        MidasContext ctx = extractContext();
        assertThat(ctx.getProductReviewReport()).isNotNull();
        assertThat(ctx.getProductReviewReport().get("verdict").asText()).isEqualTo("REJECT");
        assertThat(ctx.getProductReviewRemediationAttempts()).isEqualTo(1);
        assertThat(ctx.getRemediationDirective().get("remediation_mode").asText()).isEqualTo("SURGICAL_PATCH");
        assertThat(ctx.getLastErrorMessage())
                .contains("REJECTED")
                .contains("Implement task assignment end-to-end (API + persistence + UI)");
        assertThat(ctx.safeAuditLog())
                .anyMatch(e -> e.getSeverity() == com.midas.d3.context.AuditEntry.Severity.ERROR);
    }

    @Test
    @DisplayName("PRODUCT_REVIEW REJECT → remediate → PASS reaches COMPLETED")
    void productReview_rejectThenPass_completes() {
        drivePipelineToProductReview();
        sendSubmit(CONTROLLER_REJECT);
        assertState(MidasState.CODE_GENERATION);

        drivePipelineFromCodeGeneration();
        sendSubmit(CONTROLLER_PASS);

        assertState(MidasState.COMPLETED);
        MidasContext ctx = extractContext();
        assertThat(ctx.getProductReviewRemediationAttempts()).isEqualTo(1);
        assertThat(ctx.getRemediationDirectiveOpt()).isPresent();
        assertThat(ctx.getProductReviewReport()).isNotNull();
        assertThat(ctx.getProductReviewReport().get("verdict").asText()).isEqualTo("PASS");
        assertThat(ctx.getGeneratedSourceCode()).isNotNull();
        assertThat(ctx.getGeneratedTests()).isNotNull();
        assertThat(ctx.getSecOpsArtifacts()).isNotNull();
    }

    @Test
    @DisplayName("PRODUCT_REVIEW with malformed gate output stays in stage and increments retry")
    void productReview_invalidOutput_retries() {
        drivePipelineToProductReview();

        sendSubmit(INVALID_JSON);

        assertState(MidasState.PRODUCT_REVIEW);
        assertThat(extractContext().getValidationRetries()).isEqualTo(1);
    }

    // ── Stage 8: Human-in-the-Loop ────────────────────────────────────────────

    @Test
    @DisplayName("ANALYST_NEEDS_INFO in SYSTEM_ANALYSIS → transitions to WAITING_FOR_USER_INPUT")
    void analystNeedsInfo_movesToWaitingState() {
        sendStart("Build something");

        machine.sendEvent(Mono.just(
                MessageBuilder.withPayload(MidasEvent.ANALYST_NEEDS_INFO).build()
        )).blockLast();

        assertThat(machine.getState().getId()).isEqualTo(MidasState.WAITING_FOR_USER_INPUT);
    }

    @Test
    @DisplayName("USER_REPLIED in WAITING_FOR_USER_INPUT → returns to SYSTEM_ANALYSIS")
    void userReplied_returnsToSystemAnalysis() {
        sendStart("Build something");
        machine.sendEvent(Mono.just(
                MessageBuilder.withPayload(MidasEvent.ANALYST_NEEDS_INFO).build()
        )).blockLast();
        assertThat(machine.getState().getId()).isEqualTo(MidasState.WAITING_FOR_USER_INPUT);

        machine.sendEvent(Mono.just(MessageBuilder
                .withPayload(MidasEvent.USER_REPLIED)
                .setHeader(PipelineContextKeys.USER_REPLY_HEADER, "It's a REST API backed by PostgreSQL")
                .build()
        )).blockLast();

        assertThat(machine.getState().getId()).isEqualTo(MidasState.SYSTEM_ANALYSIS);
    }

    @Test
    @DisplayName("After USER_REPLIED, valid SUBMIT_RESULT advances past SYSTEM_ANALYSIS normally")
    void afterUserReplied_validSubmit_advancesToArchitecture() {
        sendStart("Build something");
        machine.sendEvent(Mono.just(
                MessageBuilder.withPayload(MidasEvent.ANALYST_NEEDS_INFO).build()
        )).blockLast();

        machine.sendEvent(Mono.just(MessageBuilder
                .withPayload(MidasEvent.USER_REPLIED)
                .setHeader(PipelineContextKeys.USER_REPLY_HEADER, "It's a REST API backed by PostgreSQL")
                .build()
        )).blockLast();
        assertThat(machine.getState().getId()).isEqualTo(MidasState.SYSTEM_ANALYSIS);

        sendSubmit(VALID_TECH_SPEC);

        assertThat(machine.getState().getId()).isEqualTo(MidasState.ARCHITECTURE_DESIGN);
    }

    @Test
    @DisplayName("RESET from WAITING_FOR_USER_INPUT → transitions to IDLE")
    void reset_fromWaitingState_returnsToIdle() {
        sendStart("Build something");
        machine.sendEvent(Mono.just(
                MessageBuilder.withPayload(MidasEvent.ANALYST_NEEDS_INFO).build()
        )).blockLast();
        assertThat(machine.getState().getId()).isEqualTo(MidasState.WAITING_FOR_USER_INPUT);

        machine.sendEvent(Mono.just(
                MessageBuilder.withPayload(MidasEvent.RESET).build()
        )).blockLast();

        assertThat(machine.getState().getId()).isEqualTo(MidasState.IDLE);
    }

    @Test
    @DisplayName("SUBMIT_RESULT is ignored in WAITING_FOR_USER_INPUT (no matching transition)")
    void submitResult_inWaitingState_isIgnored() {
        sendStart("Build something");
        machine.sendEvent(Mono.just(
                MessageBuilder.withPayload(MidasEvent.ANALYST_NEEDS_INFO).build()
        )).blockLast();

        sendSubmit(VALID_TECH_SPEC);

        assertThat(machine.getState().getId()).isEqualTo(MidasState.WAITING_FOR_USER_INPUT);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void sendStart(String idea) {
        machine.sendEvent(Mono.just(MessageBuilder
                .withPayload(MidasEvent.START)
                .setHeader(PipelineContextKeys.RAW_IDEA_HEADER, idea)
                .setHeader(PipelineContextKeys.RUN_ID_HEADER, UUID.randomUUID().toString())
                .build())).blockLast();
    }

    private void sendSubmit(String llmOutput) {
        machine.sendEvent(Mono.just(MessageBuilder
                .withPayload(MidasEvent.SUBMIT_RESULT)
                .setHeader(PipelineContextKeys.LLM_OUTPUT_HEADER, llmOutput)
                .build())).blockLast();
    }

    private MidasContext extractContext() {
        Object raw = machine.getExtendedState().getVariables().get(PipelineContextKeys.MIDAS_CONTEXT);
        assertThat(raw).isInstanceOf(MidasContext.class);
        return (MidasContext) raw;
    }

    private void assertState(MidasState expected) {
        assertThat(machine.getState().getId())
                .as("Expected state %s but was %s", expected, machine.getState().getId())
                .isEqualTo(expected);
    }

    /** Drives the machine through stages 1-6 so the next submission lands on PRODUCT_REVIEW. */
    private void drivePipelineToProductReview() {
        sendStart("Build a task management system");
        sendSubmit(VALID_TECH_SPEC);
        assertState(MidasState.ARCHITECTURE_DESIGN);
        sendSubmit("""
            {
              "has_external_integrations": true,
              "architecture_style": "CLIENT_SERVER",
              "tech_stack": {"language": "Java", "framework": "Spring Boot",
                             "platform_apis": [], "build_tool": "Maven"},
              "components": [{"name": "TaskController", "type": "CONTROLLER", "responsibility": "Tasks API"}],
              "file_layout": ["src/main/java/com/example/TaskController.java"],
              "data_persistence": {
                "type": "RELATIONAL",
                "schema": [{"table_name": "tasks", "columns": [{"name": "id", "type": "BIGINT", "is_primary": true, "is_nullable": false}]}]
              },
              "api_contracts": [{"method": "GET", "path": "/api/tasks", "request_payload": {}, "expected_response": {}}]
            }
            """);
        assertState(MidasState.INTEGRATION_STRATEGY);
        sendSubmit("""
            {
              "has_external_integrations": false,
              "external_services": [],
              "client_side_constraints": ["No external APIs required"]
            }
            """);
        assertState(MidasState.CODE_GENERATION);
        sendSubmit(VALID_CODE_GEN);
        assertState(MidasState.TEST_GENERATION);
        sendSubmit("""
            {
              "src/test/java/com/example/TaskControllerTest.java": "class TaskControllerTest { @Test void test() {} }"
            }
            """);
        assertState(MidasState.SECOPS_AUDIT);
        sendSubmit("""
            {
              "security_audit_report": ["No hardcoded credentials found."],
              "Dockerfile": "FROM eclipse-temurin:21-jre\\nCOPY app.jar app.jar\\nENTRYPOINT [\\"java\\",\\"-jar\\",\\"app.jar\\"]"
            }
            """);
        assertState(MidasState.PRODUCT_REVIEW);
    }

    private void drivePipelineFromCodeGeneration() {
        sendSubmit(VALID_CODE_GEN_REMEDIATED);
        assertState(MidasState.TEST_GENERATION);
        sendSubmit("""
            {
              "src/test/java/com/example/TaskControllerTest.java": "class TaskControllerTest { @Test void assignWorks() {} }"
            }
            """);
        assertState(MidasState.SECOPS_AUDIT);
        sendSubmit("""
            {
              "security_audit_report": ["No hardcoded credentials found."],
              "Dockerfile": "FROM eclipse-temurin:21-jre\\nCOPY app.jar app.jar\\nENTRYPOINT [\\"java\\",\\"-jar\\",\\"app.jar\\"]"
            }
            """);
        assertState(MidasState.PRODUCT_REVIEW);
    }
}

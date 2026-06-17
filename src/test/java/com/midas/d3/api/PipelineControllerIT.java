package com.midas.d3.api;

import com.midas.d3.security.JwtService;
import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static io.restassured.RestAssured.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * RestAssured integration tests for {@link PipelineController}.
 *
 * <p>Uses a real embedded Tomcat on a random port so every test exercises the full
 * HTTP stack (serialisation → controller → orchestrator → state machine → response).
 *
 * <p><b>Isolation contract:</b> Every test that starts a pipeline run cleans it up
 * in a {@code finally} block so subsequent tests see a consistent active-run count.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("Pipeline REST API — Integration Tests")
class PipelineControllerIT {

    @LocalServerPort
    private int port;

    /** Used to mint a valid Bearer token so every request authenticates against the secured pipeline API. */
    @Autowired
    private JwtService jwtService;

    /** Arbitrary Telegram chat ID embedded in the test token. */
    private static final long TEST_CHAT_ID = 4242L;

    // ── JSON fixtures (mirrors PipelineStateMachineTest for consistency) ──────

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
                {"case": "empty input",    "solution": "return HTTP 400"},
                {"case": "duplicate task", "solution": "return HTTP 409"}
              ],
              "non_functional_requirements": ["< 200ms p95 latency"]
            }
            """;

    private static final String VALID_ARCHITECTURE = """
            {
              "has_external_integrations": true,
              "architecture_style": "CLIENT_SERVER",
              "tech_stack": {"language": "Java", "framework": "Spring Boot",
                             "platform_apis": [], "build_tool": "Maven"},
              "components": [{"name": "TaskController", "type": "CONTROLLER",
                              "responsibility": "Expose the tasks API"}],
              "file_layout": ["src/main/java/com/example/TaskController.java"],
              "data_persistence": {
                "type": "RELATIONAL",
                "schema": [{"table_name": "tasks", "columns":
                  [{"name": "id", "type": "BIGINT", "is_primary": true, "is_nullable": false}]}]
              },
              "api_contracts": [{"method": "GET", "path": "/api/tasks",
                "request_payload": {}, "expected_response": {}}]
            }
            """;

    private static final String VALID_INTEGRATION = """
            {
              "has_external_integrations": false,
              "external_services": [],
              "client_side_constraints": ["No external APIs required"]
            }
            """;

    private static final String VALID_CODE_GEN = """
            {
              "src/main/java/com/example/TaskController.java":
                "public class TaskController { }"
            }
            """;

    private static final String VALID_TESTS = """
            {
              "src/test/java/com/example/TaskControllerTest.java":
                "class TaskControllerTest { @Test void test() {} }"
            }
            """;

    private static final String VALID_SECOPS = """
            {
              "security_audit_report": ["No hardcoded credentials found."],
              "Dockerfile": "FROM eclipse-temurin:21-jre\\nCOPY app.jar app.jar\\nENTRYPOINT [\\"java\\",\\"-jar\\",\\"app.jar\\"]",
              "docker-compose.yml": "version: '3.8'\\nservices:\\n  app:\\n    build: ."
            }
            """;

    private static final String VALID_CONTROLLER = """
            {
              "verdict": "PASS",
              "summary": "All requested features were delivered.",
              "coverage_matrix": [
                {"requested_feature": "Create task", "status": "COVERED", "evidence": "TaskController create"}
              ],
              "remediation_block": {"required_changes": [], "recommendations": []}
            }
            """;

    private static final String REJECT_CONTROLLER = """
            {
              "verdict": "REJECT",
              "summary": "Task assignment was never implemented.",
              "coverage_matrix": [
                {"requested_feature": "Assign task", "status": "MISSING", "evidence": "no assignment endpoint"}
              ],
              "remediation_block": {
                "required_changes": ["Implement task assignment end-to-end"],
                "recommendations": []
              }
            }
            """;

    // ── Setup ─────────────────────────────────────────────────────────────────

    @BeforeEach
    void configureRestAssured() {
        RestAssured.port = port;
        RestAssured.basePath = "/api/v1/pipelines";
        // The pipeline API is JWT-protected; attach a valid Bearer token to every request by default.
        String token = jwtService.generateToken(TEST_CHAT_ID);
        RestAssured.requestSpecification = new RequestSpecBuilder()
                .addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .build();
    }

    @AfterEach
    void resetRestAssured() {
        RestAssured.reset();
    }

    // ── POST / — start pipeline ───────────────────────────────────────────────

    @Test
    @DisplayName("POST / with valid idea → 201, runId not null, state=SYSTEM_ANALYSIS")
    void startPipeline_validIdea_returns201WithRunIdAndState() {
        String runId = given()
                    .contentType(ContentType.JSON)
                    .body(Map.of("rawUserIdea", "Build an e-commerce platform"))
                .when()
                    .post()
                .then()
                    .statusCode(201)
                    .body("runId", notNullValue())
                    .body("state", equalTo("SYSTEM_ANALYSIS"))
                .extract().path("runId");

        cleanup(runId);
    }

    @Test
    @DisplayName("POST / with blank idea → 400 with violation message")
    void startPipeline_blankIdea_returns400WithViolations() {
        given()
                .contentType(ContentType.JSON)
                .body(Map.of("rawUserIdea", "   "))
            .when()
                .post()
            .then()
                .statusCode(400)
                .body("status", equalTo(400))
                .body("violations", hasItem(containsString("blank")));
    }

    @Test
    @DisplayName("POST / with missing rawUserIdea field → 400")
    void startPipeline_missingField_returns400() {
        given()
                .contentType(ContentType.JSON)
                .body(Map.of())
            .when()
                .post()
            .then()
                .statusCode(400)
                .body("violations", hasItem(containsString("blank")));
    }

    @Test
    @DisplayName("POST / without Content-Type → 415 Unsupported Media Type")
    void startPipeline_noContentType_returns415() {
        given()
                .body("{\"rawUserIdea\": \"Build something\"}")
            .when()
                .post()
            .then()
                .statusCode(415);
    }

    // ── Security ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Security: request without a Bearer token → 401 Unauthorized")
    void anyEndpoint_withoutToken_returns401() {
        // Null the global default FIRST: RequestSpecBuilder.build() snapshots the current
        // RestAssured.requestSpecification, so the Bearer token attached in @BeforeEach would
        // otherwise be baked into this spec. Build the self-contained (port + path + body) spec
        // only after clearing the default so this request carries no Authorization header.
        RestAssured.requestSpecification = null;
        RequestSpecification noAuth = new RequestSpecBuilder()
                .setPort(port)
                .setBasePath("/api/v1/pipelines")
                .setContentType(ContentType.JSON)
                .setBody(Map.of("rawUserIdea", "Build something without a token"))
                .build();

        given(noAuth)
            .when()
                .post()
            .then()
                .statusCode(401)
                .body("status", equalTo(401))
                .body("error", equalTo("Unauthorized"));
    }

    @Test
    @DisplayName("Security: request with a malformed Bearer token → 401 Unauthorized")
    void anyEndpoint_withInvalidToken_returns401() {
        // Clear the global default before building so only the malformed token is sent.
        RestAssured.requestSpecification = null;
        RequestSpecification badAuth = new RequestSpecBuilder()
                .setPort(port)
                .setBasePath("/api/v1/pipelines")
                .addHeader(HttpHeaders.AUTHORIZATION, "Bearer not-a-real-jwt")
                .build();

        given(badAuth)
            .when()
                .get("/count")
            .then()
                .statusCode(401);
    }

    // ── GET /{runId}/status ───────────────────────────────────────────────────

    @Test
    @DisplayName("GET /{runId}/status → 200 with runId and state")
    void getStatus_existingRun_returns200WithState() {
        String runId = startRun("Build a scheduling system");
        try {
            given()
                .when()
                    .get("/" + runId + "/status")
                .then()
                    .statusCode(200)
                    .body("runId", equalTo(runId))
                    .body("state", notNullValue());
        } finally {
            cleanup(runId);
        }
    }

    @Test
    @DisplayName("GET /unknown/status → 404 with error body")
    void getStatus_unknownRun_returns404() {
        given()
            .when()
                .get("/totally-unknown-run-id/status")
            .then()
                .statusCode(404)
                .body("status", equalTo(404))
                .body("error", equalTo("Not Found"))
                .body("message", containsString("totally-unknown-run-id"))
                .body("timestamp", notNullValue());
    }

    // ── GET /{runId}/context ──────────────────────────────────────────────────

    @Test
    @DisplayName("GET /{runId}/context → 200 with full context fields")
    void getContext_existingRun_returnsContextSnapshot() {
        String idea = "Build a hotel booking platform";
        String runId = startRun(idea);
        try {
            given()
                .when()
                    .get("/" + runId + "/context")
                .then()
                    .statusCode(200)
                    .body("runId", equalTo(runId))
                    .body("rawUserIdea", equalTo(idea))
                    .body("state", equalTo("SYSTEM_ANALYSIS"))
                    .body("validationRetries", equalTo(0))
                    .body("createdAt", notNullValue())
                    .body("auditLog", not(empty()));
        } finally {
            cleanup(runId);
        }
    }

    @Test
    @DisplayName("GET /unknown/context → 404")
    void getContext_unknownRun_returns404() {
        given()
            .when()
                .get("/unknown-context-run/context")
            .then()
                .statusCode(404);
    }

    // ── POST /{runId}/submit ──────────────────────────────────────────────────

    @Test
    @DisplayName("POST /{runId}/submit with invalid JSON → 200, state=SYSTEM_ANALYSIS, retries=1")
    void submit_invalidJson_incrementsRetryAndStaysInStage() {
        String runId = startRun("Build an inventory system");
        try {
            given()
                    .contentType(ContentType.JSON)
                    .body(Map.of("llmOutput", "{ broken json"))
                .when()
                    .post("/" + runId + "/submit")
                .then()
                    .statusCode(200)
                    .body("state", equalTo("SYSTEM_ANALYSIS"));

            given()
                .when()
                    .get("/" + runId + "/context")
                .then()
                    .statusCode(200)
                    .body("validationRetries", equalTo(1));
        } finally {
            cleanup(runId);
        }
    }

    @Test
    @DisplayName("POST /{runId}/submit with valid tech spec → 200, state=ARCHITECTURE_DESIGN, artifact stored")
    void submit_validTechSpec_advancesToArchitectureDesign() {
        String runId = startRun("Build a task management system");
        try {
            given()
                    .contentType(ContentType.JSON)
                    .body(Map.of("llmOutput", VALID_TECH_SPEC))
                .when()
                    .post("/" + runId + "/submit")
                .then()
                    .statusCode(200)
                    .body("state", equalTo("ARCHITECTURE_DESIGN"));

            given()
                .when()
                    .get("/" + runId + "/context")
                .then()
                    .statusCode(200)
                    .body("technicalSpec.business_goal",
                            equalTo("Build a task management system"))
                    .body("validationRetries", equalTo(0));
        } finally {
            cleanup(runId);
        }
    }

    @Test
    @DisplayName("POST /unknown/submit → 404")
    void submit_unknownRun_returns404() {
        given()
                .contentType(ContentType.JSON)
                .body(Map.of("llmOutput", "{}"))
            .when()
                .post("/non-existent-run/submit")
            .then()
                .statusCode(404);
    }

    @Test
    @DisplayName("POST /{runId}/submit with blank llmOutput → 400")
    void submit_blankOutput_returns400() {
        String runId = startRun("Build a logging service");
        try {
            given()
                    .contentType(ContentType.JSON)
                    .body(Map.of("llmOutput", ""))
                .when()
                    .post("/" + runId + "/submit")
                .then()
                    .statusCode(400)
                    .body("violations", hasItem(containsString("blank")));
        } finally {
            cleanup(runId);
        }
    }

    @Test
    @DisplayName("Three invalid submissions exhaust retries → state=ERROR")
    void submit_threeInvalidSubmissions_movesToError() {
        String runId = startRun("Build a messaging app");
        try {
            Map<String, String> badBody = Map.of("llmOutput", "{ bad json");
            for (int i = 0; i < 3; i++) {
                given()
                        .contentType(ContentType.JSON)
                        .body(badBody)
                    .when()
                        .post("/" + runId + "/submit")
                    .then()
                        .statusCode(200);
            }
            given()
                .when()
                    .get("/" + runId + "/status")
                .then()
                    .statusCode(200)
                    .body("state", equalTo("ERROR"));
        } finally {
            cleanup(runId);
        }
    }

    // ── DELETE /{runId} ───────────────────────────────────────────────────────

    @Test
    @DisplayName("DELETE /{runId} → 204 and subsequent GET → 404")
    void reset_existingRun_returns204AndRunIsGone() {
        String runId = startRun("Build a blog platform");

        delete("/" + runId).then().statusCode(204);

        given()
            .when()
                .get("/" + runId + "/status")
            .then()
                .statusCode(404);
    }

    @Test
    @DisplayName("DELETE /unknown-id → 404")
    void reset_unknownRun_returns404() {
        delete("/unknown-run-to-reset").then().statusCode(404);
    }

    // ── GET /count ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /count → 200 with non-negative activeRuns")
    void getCount_returns200WithNonNegativeCount() {
        given()
            .when()
                .get("/count")
            .then()
                .statusCode(200)
                .body("activeRuns", greaterThanOrEqualTo(0));
    }

    @Test
    @DisplayName("GET /count reflects newly started pipeline run")
    void getCount_incrementsAfterStart_andDecrementsAfterReset() {
        int before = given().get("/count").then().extract().<Integer>path("activeRuns");

        String runId = startRun("Count-check pipeline");
        try {
            int after = given().get("/count").then().extract().<Integer>path("activeRuns");
            assertThat(after).isGreaterThan(before);
        } finally {
            cleanup(runId);
        }
    }

    // ── Full happy-path through all 7 stages ─────────────────────────────────

    @Test
    @DisplayName("Happy path: start + 7 valid submissions → COMPLETED, all artifacts present")
    void happyPath_fullPipeline_completesWithAllArtifacts() {
        String runId = startRun("Build a full-stack task management system");
        try {
            submit(runId, VALID_TECH_SPEC,    "ARCHITECTURE_DESIGN");
            submit(runId, VALID_ARCHITECTURE, "INTEGRATION_STRATEGY");
            submit(runId, VALID_INTEGRATION,  "CODE_GENERATION");
            submit(runId, VALID_CODE_GEN,     "TEST_GENERATION");
            submit(runId, VALID_TESTS,        "SECOPS_AUDIT");
            submit(runId, VALID_SECOPS,       "PRODUCT_REVIEW");
            submit(runId, VALID_CONTROLLER,   "COMPLETED");

            given()
                .when()
                    .get("/" + runId + "/context")
                .then()
                    .statusCode(200)
                    .body("state", equalTo("COMPLETED"))
                    .body("technicalSpec",      notNullValue())
                    .body("architectureDesign", notNullValue())
                    .body("integrationStrategy",notNullValue())
                    .body("generatedSourceCode",notNullValue())
                    .body("generatedTests",     notNullValue())
                    .body("secOpsArtifacts",    notNullValue())
                    .body("productReviewReport",notNullValue())
                    .body("validationRetries",  equalTo(0))
                    .body("auditLog.size()",    greaterThanOrEqualTo(8));
        } finally {
            cleanup(runId);
        }
    }

    @Test
    @DisplayName("PRODUCT_REVIEW REJECT → ERROR with productReviewReport attached")
    void productReview_reject_routesToErrorWithReport() {
        String runId = startRun("Build a full-stack task management system");
        try {
            submit(runId, VALID_TECH_SPEC,    "ARCHITECTURE_DESIGN");
            submit(runId, VALID_ARCHITECTURE, "INTEGRATION_STRATEGY");
            submit(runId, VALID_INTEGRATION,  "CODE_GENERATION");
            submit(runId, VALID_CODE_GEN,     "TEST_GENERATION");
            submit(runId, VALID_TESTS,        "SECOPS_AUDIT");
            submit(runId, VALID_SECOPS,       "PRODUCT_REVIEW");
            submit(runId, REJECT_CONTROLLER,  "ERROR");

            given()
                .when()
                    .get("/" + runId + "/context")
                .then()
                    .statusCode(200)
                    .body("state", equalTo("ERROR"))
                    .body("productReviewReport.verdict", equalTo("REJECT"))
                    .body("lastErrorMessage", notNullValue());
        } finally {
            cleanup(runId);
        }
    }

    @Test
    @DisplayName("Retry-then-success: one invalid + one valid submission advances stage")
    void retryThenSuccess_advancesStage() {
        String runId = startRun("Build a CRM with retry test");
        try {
            given()
                    .contentType(ContentType.JSON)
                    .body(Map.of("llmOutput", "{ bad json"))
                .when()
                    .post("/" + runId + "/submit")
                .then()
                    .statusCode(200)
                    .body("state", equalTo("SYSTEM_ANALYSIS"));

            submit(runId, VALID_TECH_SPEC, "ARCHITECTURE_DESIGN");

            given()
                .when()
                    .get("/" + runId + "/context")
                .then()
                    .statusCode(200)
                    .body("validationRetries", equalTo(0));
        } finally {
            cleanup(runId);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Starts a pipeline run and returns the generated runId (asserts 201). */
    private String startRun(String idea) {
        return given()
                    .contentType(ContentType.JSON)
                    .body(Map.of("rawUserIdea", idea))
                .when()
                    .post()
                .then()
                    .statusCode(201)
                .extract().path("runId");
    }

    /** Submits llmOutput and asserts the expected resulting state. */
    private void submit(String runId, String llmOutput, String expectedState) {
        given()
                .contentType(ContentType.JSON)
                .body(Map.of("llmOutput", llmOutput))
            .when()
                .post("/" + runId + "/submit")
            .then()
                .statusCode(200)
                .body("state", equalTo(expectedState));
    }

    /** Silently resets a run; swallows errors so test teardown never masks real failures. */
    private void cleanup(String runId) {
        if (runId == null) return;
        try {
            delete("/" + runId);
        } catch (Exception ignored) {
        }
    }
}

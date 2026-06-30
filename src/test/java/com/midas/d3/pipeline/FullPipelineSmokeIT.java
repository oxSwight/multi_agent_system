package com.midas.d3.pipeline;

import com.midas.d3.agent.AgentOrchestrationService;
import com.midas.d3.context.MidasContext;
import com.midas.d3.llm.LlmCallRequest;
import com.midas.d3.llm.LlmCallResult;
import com.midas.d3.llm.LlmClient;
import com.midas.d3.statemachine.MidasState;
import com.midas.d3.statemachine.PipelineOrchestrator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full-pipeline Spring-context smoke test (D3).
 *
 * <p>Boots the real application context and drives one pipeline run through <b>every</b> stage —
 * SYSTEM_ANALYSIS → ARCHITECTURE_DESIGN → INTEGRATION_STRATEGY → CODE_GENERATION → TEST_GENERATION
 * → BUILD_VERIFICATION → SECOPS_AUDIT → PRODUCT_REVIEW → COMPLETED — to prove the state-machine
 * topology, agents, validators, per-file coordinators, build gate and context-reducer wiring all
 * connect end-to-end.
 *
 * <p><b>Zero-cost:</b> the only seam replaced is {@link LlmClient}. A {@link MockLlmClient} (marked
 * {@code @Primary}) returns minimal, schema-valid output per {@link LlmCallRequest#getStage() stage},
 * so no real model is ever called. The deterministic {@code BUILD_VERIFICATION} gate needs no mock:
 * the generated project carries no build descriptor, so {@code BuildVerificationService} returns a
 * {@code SUCCESS} "skipped" report and {@code BUILD_CHOICE} advances to the SecOps audit exactly as a
 * real green build would.
 *
 * <p>The pipeline is started via {@link PipelineOrchestrator#startPipeline(String)} (manual mode) and
 * driven synchronously by {@link AgentOrchestrationService#runCurrentStage(String)} until it reaches a
 * terminal state — the synchronous analogue of polling the async (Telegram) auto-drive loop, but
 * without its per-agent rate-limit throttle.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(FullPipelineSmokeIT.MockLlmConfig.class)
@DisplayName("Full pipeline — Spring context smoke test (mocked LLM → COMPLETED)")
class FullPipelineSmokeIT {

    /** Safety cap on the drive loop: the happy path is 8 stages, so this can never legitimately trip. */
    private static final int MAX_STAGES = 20;

    @Autowired
    private PipelineOrchestrator orchestrator;

    @Autowired
    private AgentOrchestrationService agentOrchestrationService;

    @Autowired
    private MockLlmClient mockLlmClient;

    @Test
    @DisplayName("a zero-cost mocked run drives the whole topology to COMPLETED with every artifact populated")
    void fullPipeline_withMockedLlm_reachesCompleted() {
        String runId = orchestrator.startPipeline("Build a full-stack task management system");
        try {
            // Drive each stage synchronously, polling the state until the machine settles in a
            // terminal state (COMPLETED or ERROR) or the safety cap trips.
            MidasState state = orchestrator.getState(runId);
            int stages = 0;
            while (state != MidasState.COMPLETED && state != MidasState.ERROR && stages < MAX_STAGES) {
                state = agentOrchestrationService.runCurrentStage(runId);
                stages++;
            }

            assertThat(state)
                    .as("pipeline should reach COMPLETED, not stall or error out")
                    .isEqualTo(MidasState.COMPLETED);

            // The topology actually carried artifacts through every stage, not just flipped a flag.
            MidasContext ctx = orchestrator.getContext(runId).orElseThrow();
            assertThat(ctx.getTechnicalSpec()).as("system analysis").isNotNull();
            assertThat(ctx.getArchitectureDesign()).as("architecture").isNotNull();
            assertThat(ctx.getIntegrationStrategy()).as("integration").isNotNull();
            assertThat(ctx.getGeneratedSourceCode()).as("code generation").isNotNull();
            assertThat(ctx.getGeneratedTests()).as("test generation").isNotNull();
            assertThat(ctx.getSecOpsArtifacts()).as("secops audit").isNotNull();
            assertThat(ctx.getProductReviewReport()).as("product review").isNotNull();
            assertThat(ctx.getValidationRetries())
                    .as("every stage passed on its first mocked attempt")
                    .isZero();

            // Zero real model calls: the mock was the only LLM invoked, once per LLM stage.
            assertThat(mockLlmClient.callCount())
                    .as("seven LLM stages each call the mock at least once")
                    .isGreaterThanOrEqualTo(7);
        } finally {
            orchestrator.reset(runId);
        }
    }

    // ── Mock LLM wiring ─────────────────────────────────────────────────────────

    @TestConfiguration
    static class MockLlmConfig {
        @Bean
        @Primary
        MockLlmClient mockLlmClient() {
            return new MockLlmClient();
        }
    }

    /**
     * Stage-aware {@link LlmClient} double. Switches on the requesting {@link MidasState} and returns
     * the minimal output each stage's validator accepts: a JSON artifact for the single-call stages,
     * and a single markdown code block for the per-file CODE/TEST generation strategies.
     */
    static final class MockLlmClient implements LlmClient {

        private final AtomicInteger calls = new AtomicInteger();

        int callCount() {
            return calls.get();
        }

        @Override
        public LlmCallResult call(LlmCallRequest request) {
            calls.incrementAndGet();
            String text = switch (request.getStage()) {
                case SYSTEM_ANALYSIS      -> TECH_SPEC;
                case ARCHITECTURE_DESIGN  -> ARCHITECTURE;
                case INTEGRATION_STRATEGY -> INTEGRATION;
                case CODE_GENERATION      -> CODE_BLOCK;
                case TEST_GENERATION      -> TEST_BLOCK;
                case SECOPS_AUDIT         -> SECOPS;
                case PRODUCT_REVIEW       -> CONTROLLER_PASS;
                default -> throw new IllegalStateException(
                        "MockLlmClient received an unexpected stage: " + request.getStage());
            };
            // STOP (not MAX_TOKENS) so the truncation guard treats the response as complete.
            return LlmCallResult.of(text, "mock-model", 16, 32, "STOP");
        }

        @Override
        public String defaultModelId() {
            return "mock-model";
        }
    }

    // ── Per-stage fixtures (single-call stages mirror PipelineControllerIT's validated shapes) ──

    private static final String TECH_SPEC = """
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

    private static final String ARCHITECTURE = """
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
                "request_params": [], "response_format": {"type": "json", "fields": ["id", "title"]}}]
            }
            """;

    private static final String INTEGRATION = """
            {
              "has_external_integrations": false,
              "external_services": [],
              "client_side_constraints": ["No external APIs required"]
            }
            """;

    private static final String SECOPS = """
            {
              "security_audit_report": ["No hardcoded credentials found."],
              "Dockerfile": "FROM eclipse-temurin:21-jre\\nCOPY app.jar app.jar\\nENTRYPOINT [\\"java\\",\\"-jar\\",\\"app.jar\\"]",
              "docker-compose.yml": "version: '3.8'\\nservices:\\n  app:\\n    build: ."
            }
            """;

    private static final String CONTROLLER_PASS = """
            {
              "verdict": "PASS",
              "summary": "All requested features were delivered.",
              "coverage_matrix": [
                {"requested_feature": "Create task", "status": "COVERED", "evidence": "create-task in src/main/java/com/example/TaskController.java"}
              ],
              "remediation_block": {"required_changes": [], "recommendations": []}
            }
            """;

    // CODE/TEST generation run per-file: the strategy expects a single markdown code block of raw
    // source (no JSON envelope, no placeholder markers), keyed to the architecture's file_layout.

    private static final String CODE_BLOCK = """
            ```java
            package com.example;

            import java.util.ArrayList;
            import java.util.List;

            public class TaskController {

                private final List<String> tasks = new ArrayList<>();

                public List<String> listTasks() {
                    return List.copyOf(tasks);
                }

                public void createTask(String title) {
                    tasks.add(title);
                }
            }
            ```
            """;

    private static final String TEST_BLOCK = """
            ```java
            package com.example;

            import org.junit.jupiter.api.Test;
            import org.junit.jupiter.api.Assertions;

            class TaskControllerTest {

                @Test
                void createThenListReturnsTheTask() {
                    TaskController controller = new TaskController();
                    controller.createTask("Write the smoke test");
                    Assertions.assertEquals(1, controller.listTasks().size());
                }
            }
            ```
            """;
}

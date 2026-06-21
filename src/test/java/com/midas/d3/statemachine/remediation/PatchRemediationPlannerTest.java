package com.midas.d3.statemachine.remediation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.midas.d3.config.JacksonConfig;
import com.midas.d3.context.MidasContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PatchRemediationPlanner")
class PatchRemediationPlannerTest {

    private ObjectMapper objectMapper;
    private PatchRemediationPlanner planner;

    @BeforeEach
    void setUp() {
        objectMapper = new JacksonConfig().objectMapper();
        planner = new PatchRemediationPlanner(objectMapper, 512);
    }

    @Test
    @DisplayName("manifest present resolves affected paths and features for surgical patch")
    void plan_manifestPresent_resolvesSurgicalPatch() throws Exception {
        MidasContext context = baseContext()
                .withFeatureManifest(objectMapper.readTree("""
                        [
                          {
                            "feature_id": "assign-task",
                            "feature_name": "Assign task",
                            "files": ["src/main/java/com/example/TaskController.java"],
                            "entry_points": ["TaskController"]
                          }
                        ]
                        """))
                .withGeneratedSourceCode(objectMapper.readTree("""
                        {"src/main/java/com/example/TaskController.java": "public class TaskController { }"}
                        """));

        JsonNode report = objectMapper.readTree("""
                {
                  "verdict": "REJECT",
                  "summary": "Assignment missing.",
                  "coverage_matrix": [
                    {
                      "requested_feature": "Assign task",
                      "status": "MISSING",
                      "evidence": "assign-task absent from src/main/java/com/example/TaskController.java"
                    }
                  ],
                  "remediation_block": {"required_changes": ["Implement assignment"], "recommendations": []}
                }
                """);

        RemediationPlan plan = planner.plan(context, report);

        assertThat(plan.mode()).isEqualTo(RemediationMode.SURGICAL_PATCH);
        assertThat(plan.affectedFeatures()).containsExactly("assign-task");
        assertThat(plan.affectedPaths()).containsExactly("src/main/java/com/example/TaskController.java");
    }

    @Test
    @DisplayName("manifest absent falls back to evidence paths for surgical patch")
    void plan_manifestAbsent_fallsBackToEvidencePaths() throws Exception {
        MidasContext context = baseContext()
                .withGeneratedSourceCode(objectMapper.readTree("""
                        {"src/main/java/com/example/TaskController.java": "public class TaskController { }"}
                        """));

        JsonNode report = objectMapper.readTree("""
                {
                  "verdict": "REJECT",
                  "summary": "Assignment missing.",
                  "coverage_matrix": [
                    {
                      "requested_feature": "Assign task",
                      "status": "MISSING",
                      "evidence": "assign-task absent from src/main/java/com/example/TaskController.java"
                    }
                  ],
                  "remediation_block": {"required_changes": ["Implement assignment"], "recommendations": []}
                }
                """);

        RemediationPlan plan = planner.plan(context, report);

        assertThat(plan.mode()).isEqualTo(RemediationMode.SURGICAL_PATCH);
        assertThat(plan.affectedFeatures()).containsExactly("assign-task");
        assertThat(plan.affectedPaths()).containsExactly("src/main/java/com/example/TaskController.java");
    }

    @Test
    @DisplayName("HYBRID wide gap affecting majority of layout forces full regen")
    void plan_hybridWideGap_forcesFullRegen() throws Exception {
        MidasContext context = baseContext()
                .withArchitectureDesign(objectMapper.readTree("""
                        {
                          "architecture_style": "CLIENT_SERVER",
                          "file_layout": [
                            "src/popup.ts",
                            "src/background.ts",
                            "src/main/java/com/example/TaskController.java",
                            "src/main/java/com/example/TaskRepository.java"
                          ]
                        }
                        """))
                .withFeatureManifest(objectMapper.readTree("""
                        [
                          {
                            "feature_id": "popup-ui",
                            "feature_name": "Popup UI",
                            "files": ["src/popup.ts"],
                            "entry_points": ["renderPopup"]
                          },
                          {
                            "feature_id": "background-sync",
                            "feature_name": "Background sync",
                            "files": ["src/background.ts"],
                            "entry_points": ["sync"]
                          },
                          {
                            "feature_id": "tasks-api",
                            "feature_name": "Tasks API",
                            "files": ["src/main/java/com/example/TaskController.java"],
                            "entry_points": ["TaskController"]
                          },
                          {
                            "feature_id": "task-persistence",
                            "feature_name": "Task persistence",
                            "files": ["src/main/java/com/example/TaskRepository.java"],
                            "entry_points": ["TaskRepository"]
                          }
                        ]
                        """))
                .withGeneratedSourceCode(objectMapper.readTree("""
                        {
                          "src/popup.ts": "export function renderPopup() {}",
                          "src/background.ts": "export function sync() {}",
                          "src/main/java/com/example/TaskController.java": "class TaskController {}",
                          "src/main/java/com/example/TaskRepository.java": "class TaskRepository {}"
                        }
                        """));

        JsonNode report = objectMapper.readTree("""
                {
                  "verdict": "REJECT",
                  "summary": "Multiple surfaces incomplete.",
                  "coverage_matrix": [
                    {"requested_feature": "Popup UI", "status": "MISSING", "evidence": "popup-ui missing in src/popup.ts"},
                    {"requested_feature": "Background sync", "status": "PARTIAL", "evidence": "background-sync incomplete in src/background.ts"},
                    {"requested_feature": "Tasks API", "status": "MISSING", "evidence": "tasks-api missing in src/main/java/com/example/TaskController.java"}
                  ],
                  "remediation_block": {"required_changes": ["Complete HYBRID delivery"], "recommendations": []}
                }
                """);

        RemediationPlan plan = planner.plan(context, report);

        assertThat(plan.mode()).isEqualTo(RemediationMode.FULL_REGEN);
        assertThat(plan.affectedPaths()).isEmpty();
        assertThat(plan.affectedFeatures()).isEmpty();
    }

    @Test
    @DisplayName("empty coverage gaps force full regen")
    void plan_emptyGaps_forcesFullRegen() throws Exception {
        MidasContext context = baseContext()
                .withFeatureManifest(objectMapper.readTree("""
                        [
                          {
                            "feature_id": "create-task",
                            "feature_name": "Create task",
                            "files": ["src/main/java/com/example/TaskController.java"],
                            "entry_points": ["TaskController"]
                          }
                        ]
                        """));

        JsonNode report = objectMapper.readTree("""
                {
                  "verdict": "REJECT",
                  "summary": "Rejected without actionable gaps.",
                  "coverage_matrix": [
                    {"requested_feature": "Create task", "status": "COVERED", "evidence": "create-task in src/main/java/com/example/TaskController.java"}
                  ],
                  "remediation_block": {"required_changes": ["Polish UX"], "recommendations": []}
                }
                """);

        RemediationPlan plan = planner.plan(context, report);

        assertThat(plan.mode()).isEqualTo(RemediationMode.FULL_REGEN);
        assertThat(plan.affectedPaths()).isEmpty();
        assertThat(plan.affectedFeatures()).isEmpty();
    }

    @Test
    @DisplayName("patch context exceeding 75 percent of cap forces full regen")
    void plan_oversizedPatchContext_forcesFullRegen() throws Exception {
        String largeBody = "x".repeat(400_000);
        ObjectNode source = objectMapper.createObjectNode();
        source.put("src/main/java/com/example/TaskController.java", largeBody);

        MidasContext context = baseContext()
                .withFeatureManifest(objectMapper.readTree("""
                        [
                          {
                            "feature_id": "assign-task",
                            "feature_name": "Assign task",
                            "files": ["src/main/java/com/example/TaskController.java"],
                            "entry_points": ["TaskController"]
                          }
                        ]
                        """))
                .withGeneratedSourceCode(source);

        JsonNode report = objectMapper.readTree("""
                {
                  "verdict": "REJECT",
                  "summary": "Assignment missing.",
                  "coverage_matrix": [
                    {
                      "requested_feature": "Assign task",
                      "status": "MISSING",
                      "evidence": "assign-task absent from src/main/java/com/example/TaskController.java"
                    }
                  ],
                  "remediation_block": {"required_changes": ["Implement assignment"], "recommendations": []}
                }
                """);

        RemediationPlan plan = planner.plan(context, report);

        assertThat(plan.mode()).isEqualTo(RemediationMode.FULL_REGEN);
    }

    private MidasContext baseContext() throws Exception {
        return MidasContext.start("Build tasks", "run-planner")
                .withArchitectureDesign(objectMapper.readTree("""
                        {
                          "architecture_style": "CLIENT_SERVER",
                          "file_layout": ["src/main/java/com/example/TaskController.java"]
                        }
                        """));
    }
}

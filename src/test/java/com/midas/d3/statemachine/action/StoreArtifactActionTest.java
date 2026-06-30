package com.midas.d3.statemachine.action;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.midas.d3.config.JacksonConfig;
import com.midas.d3.context.MidasContext;
import com.midas.d3.statemachine.AgentDispatcher;
import com.midas.d3.statemachine.MidasEvent;
import com.midas.d3.statemachine.MidasState;
import com.midas.d3.statemachine.PipelineContextKeys;
import com.midas.d3.statemachine.PipelineTopology;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.statemachine.ExtendedState;
import org.springframework.statemachine.StateContext;

import java.util.HashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("StoreArtifactAction")
class StoreArtifactActionTest {

    @Mock private AgentDispatcher agentDispatcher;
    @Mock private PipelineCompletionAction pipelineCompletionAction;
    @Mock private PipelineTopology topology;
    @Mock private StateContext<MidasState, MidasEvent> stateContext;
    @Mock private ExtendedState extendedState;

    private ObjectMapper objectMapper;
    private StoreArtifactAction action;
    private Map<Object, Object> vars;

    @BeforeEach
    void setUp() {
        objectMapper = new JacksonConfig().objectMapper();
        action = new StoreArtifactAction(agentDispatcher, pipelineCompletionAction, topology, objectMapper);
        vars = new HashMap<>();
        when(stateContext.getExtendedState()).thenReturn(extendedState);
        when(extendedState.getVariables()).thenReturn(vars);
    }

    @Test
    @DisplayName("CODE_GENERATION envelope stores source_files and feature_manifest separately")
    void execute_codeGenerationEnvelope_splitsStorage() throws Exception {
        MidasContext ctx = MidasContext.start("Build app", "run-store-001");
        var envelope = objectMapper.readTree("""
                {
                  "source_files": {
                    "src/App.java": "public class App {}"
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
                """);

        vars.put(PipelineContextKeys.MIDAS_CONTEXT, ctx);
        vars.put(PipelineContextKeys.LAST_VALIDATED_NODE, envelope);
        vars.put(PipelineContextKeys.PENDING_STAGE, MidasState.CODE_GENERATION);
        when(topology.isProcessingStage(MidasState.CODE_GENERATION)).thenReturn(true);
        when(topology.nextStage(eq(MidasState.CODE_GENERATION), any(MidasContext.class)))
                .thenReturn(MidasState.TEST_GENERATION);

        action.execute(stateContext);

        MidasContext updated = (MidasContext) vars.get(PipelineContextKeys.MIDAS_CONTEXT);
        assertThat(updated.getGeneratedSourceCode()).isNotNull();
        assertThat(updated.getGeneratedSourceCode().has("src/App.java")).isTrue();
        assertThat(updated.getFeatureManifest()).isNotNull();
        assertThat(updated.getFeatureManifest()).hasSize(1);
        assertThat(updated.getFeatureManifest().get(0).get("feature_id").asText()).isEqualTo("run-app");
        assertThat(vars).doesNotContainKey(PipelineContextKeys.LAST_VALIDATED_NODE);
        verifyNoInteractions(pipelineCompletionAction);
    }

    @Test
    @DisplayName("CODE_GENERATION merged surgical patch envelope stores source and retained manifest")
    void execute_mergedSurgicalPatchEnvelope_splitsStorage() throws Exception {
        MidasContext ctx = MidasContext.start("Build app", "run-patch-store")
                .withGeneratedSourceCode(objectMapper.readTree("""
                        {"src/main/java/com/example/TaskController.java":"public class TaskController { }"}
                        """))
                .withFeatureManifest(objectMapper.readTree("""
                        [{
                          "feature_id":"create-task",
                          "feature_name":"Create task",
                          "files":["src/main/java/com/example/TaskController.java"],
                          "entry_points":["TaskController"]
                        }]
                        """));
        var mergedEnvelope = objectMapper.readTree("""
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
                    }
                  ]
                }
                """);

        vars.put(PipelineContextKeys.MIDAS_CONTEXT, ctx);
        vars.put(PipelineContextKeys.LAST_VALIDATED_NODE, mergedEnvelope);
        vars.put(PipelineContextKeys.PENDING_STAGE, MidasState.CODE_GENERATION);
        when(topology.isProcessingStage(MidasState.CODE_GENERATION)).thenReturn(true);
        when(topology.nextStage(eq(MidasState.CODE_GENERATION), any(MidasContext.class)))
                .thenReturn(MidasState.TEST_GENERATION);

        action.execute(stateContext);

        MidasContext updated = (MidasContext) vars.get(PipelineContextKeys.MIDAS_CONTEXT);
        assertThat(updated.getGeneratedSourceCode()).isNotNull();
        assertThat(updated.getGeneratedSourceCode()
                .get("src/main/java/com/example/TaskController.java").asText()).contains("assign");
        assertThat(updated.getFeatureManifest()).isNotNull();
        assertThat(updated.getFeatureManifest()).hasSize(2);
        assertThat(updated.getFeatureManifest().get(1).get("feature_id").asText()).isEqualTo("assign-task");
        assertThat(vars).doesNotContainKey(PipelineContextKeys.LAST_VALIDATED_NODE);
        verifyNoInteractions(pipelineCompletionAction);
    }

    @Test
    @DisplayName("BUILD_VERIFICATION stores the build report and an advisory quality score")
    void execute_buildVerification_storesQualityScore() throws Exception {
        MidasContext ctx = MidasContext.start("Build a task API", "run-bv-001")
                .withTechnicalSpec(objectMapper.readTree("""
                        {"business_goal":"task api",
                         "runtime_environment":{"deployment_target":"CLOUD_SERVICE","execution_model":"SERVER_SIDE"},
                         "core_features":[{"id":"f","name":"F",
                           "acceptance_criteria":[{"id":"ctrl","description":"controller","must_exist":"TaskController.java"}]}]}
                        """))
                .withGeneratedSourceCode(objectMapper.readTree(
                        "{\"a/TaskController.java\":\"public class TaskController {}\"}"));
        var buildReport = objectMapper.readTree("{\"build_status\":\"SUCCESS\",\"tool\":\"MAVEN\",\"summary\":\"ok\"}");

        vars.put(PipelineContextKeys.MIDAS_CONTEXT, ctx);
        vars.put(PipelineContextKeys.LAST_VALIDATED_NODE, buildReport);
        vars.put(PipelineContextKeys.PENDING_STAGE, MidasState.BUILD_VERIFICATION);
        when(topology.isProcessingStage(MidasState.BUILD_VERIFICATION)).thenReturn(true);
        when(topology.nextStage(eq(MidasState.BUILD_VERIFICATION), any(MidasContext.class)))
                .thenReturn(MidasState.SECOPS_AUDIT);

        action.execute(stateContext);

        MidasContext updated = (MidasContext) vars.get(PipelineContextKeys.MIDAS_CONTEXT);
        assertThat(updated.getBuildReport()).isNotNull();
        JsonNode score = updated.getQualityScore();
        assertThat(score).isNotNull();
        assertThat(score.get("build_passed").asBoolean()).isTrue();
        // must_exist (TaskController.java) satisfied + build SUCCESS → full score
        assertThat(score.get("overall").asDouble()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Missing MidasContext is a no-op")
    void execute_missingContext_noOp() throws Exception {
        var envelope = objectMapper.readTree("""
                {
                  "source_files": {"src/App.java": "public class App {}"},
                  "feature_manifest": []
                }
                """);
        vars.put(PipelineContextKeys.LAST_VALIDATED_NODE, envelope);
        vars.put(PipelineContextKeys.PENDING_STAGE, MidasState.CODE_GENERATION);

        action.execute(stateContext);

        assertThat(vars).doesNotContainKey(PipelineContextKeys.MIDAS_CONTEXT);
    }
}

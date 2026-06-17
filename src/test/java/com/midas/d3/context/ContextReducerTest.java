package com.midas.d3.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.midas.d3.config.JacksonConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.*;

class ContextReducerTest {

    private ObjectMapper objectMapper;
    private ContextReducer reducer;

    @BeforeEach
    void setUp() {
        objectMapper = new JacksonConfig().objectMapper();
        reducer = new ContextReducer(objectMapper);
    }

    @Test
    void reduce_systemAnalyst_needsNoArtifacts() {
        var ctx = MidasContext.start("Build a todo app", "run-001");
        var view = reducer.reduce(ctx, ContextReducer.AgentRole.SYSTEM_ANALYST);

        assertThat(view.getAgentName()).isEqualTo("SYSTEM_ANALYST");
        assertThat(view.getRawUserIdea()).isEqualTo("Build a todo app");
        assertThat(view.safeArtifacts()).isEmpty();
    }

    @Test
    void reduce_softwareArchitect_requiresTechnicalSpec() throws Exception {
        var spec = objectMapper.readTree("{\"business_goal\":\"test\"}");
        var ctx = MidasContext.start("Build a todo app", "run-001")
                .withTechnicalSpec(spec);

        var view = reducer.reduce(ctx, ContextReducer.AgentRole.SOFTWARE_ARCHITECT);
        assertThat(view.safeArtifacts()).containsKey("technicalSpec");
    }

    @Test
    void reduce_softwareArchitect_missingSpec_throwsIllegalArgument() {
        var ctx = MidasContext.start("idea", "run-001");
        assertThatThrownBy(() -> reducer.reduce(ctx, ContextReducer.AgentRole.SOFTWARE_ARCHITECT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("technicalSpec");
    }

    // ── Skip-aware (soft) dependencies ────────────────────────────────────────

    @Test
    void reduce_implementationEngineer_withAllArtifacts_includesIntegrationStrategy() throws Exception {
        var spec = objectMapper.readTree("{\"business_goal\":\"test\"}");
        var arch = objectMapper.readTree("{\"architecture_style\":\"CLIENT_ONLY\"}");
        var integration = objectMapper.readTree("{\"has_external_integrations\":true}");
        var ctx = MidasContext.start("Build an app", "run-001")
                .withTechnicalSpec(spec)
                .withArchitectureDesign(arch)
                .withIntegrationStrategy(integration);

        var view = reducer.reduce(ctx, ContextReducer.AgentRole.IMPLEMENTATION_ENGINEER);

        assertThat(view.safeArtifacts())
                .containsKeys("technicalSpec", "architectureDesign", "integrationStrategy");
    }

    @Test
    void reduce_implementationEngineer_missingOptionalIntegration_skipsGracefully() throws Exception {
        // Simulates dynamic routing skipping the Integration stage (no external services):
        // the reducer must still deliver the required upstream context, just without integrationStrategy.
        var spec = objectMapper.readTree("{\"business_goal\":\"test\"}");
        var arch = objectMapper.readTree("{\"architecture_style\":\"CLIENT_ONLY\"}");
        var ctx = MidasContext.start("Build a self-contained tool", "run-001")
                .withTechnicalSpec(spec)
                .withArchitectureDesign(arch);
        // integrationStrategy intentionally absent

        var view = reducer.reduce(ctx, ContextReducer.AgentRole.IMPLEMENTATION_ENGINEER);

        assertThat(view.safeArtifacts())
                .containsKeys("technicalSpec", "architectureDesign")
                .doesNotContainKey("integrationStrategy");
    }

    @Test
    void reduce_implementationEngineer_missingRequiredArtifact_stillThrows() throws Exception {
        // architectureDesign is REQUIRED → fail-fast even though integrationStrategy is soft.
        var spec = objectMapper.readTree("{\"business_goal\":\"test\"}");
        var ctx = MidasContext.start("Build an app", "run-001")
                .withTechnicalSpec(spec);

        assertThatThrownBy(() -> reducer.reduce(ctx, ContextReducer.AgentRole.IMPLEMENTATION_ENGINEER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("architectureDesign");
    }

    @Test
    void reduce_controller_withRequiredArtifacts_includesTechnicalSpecAndSecOps() throws Exception {
        var spec = objectMapper.readTree("{\"business_goal\":\"test\"}");
        var secOps = objectMapper.readTree("{\"release_artifacts\":{\"app.jar\":\"binary\"}}");
        var ctx = MidasContext.start("Build an app", "run-001")
                .withTechnicalSpec(spec)
                .withSecOpsArtifacts(secOps);

        var view = reducer.reduce(ctx, ContextReducer.AgentRole.CONTROLLER);

        assertThat(view.getAgentName()).isEqualTo("CONTROLLER");
        assertThat(view.safeArtifacts())
                .containsKeys("technicalSpec", "secOpsArtifacts");
    }

    @Test
    void reduce_controller_missingTechnicalSpec_throwsIllegalArgument() throws Exception {
        var secOps = objectMapper.readTree("{\"release_artifacts\":{}}");
        var ctx = MidasContext.start("Build an app", "run-001")
                .withSecOpsArtifacts(secOps);

        assertThatThrownBy(() -> reducer.reduce(ctx, ContextReducer.AgentRole.CONTROLLER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("technicalSpec");
    }

    @Test
    void reduce_controller_missingSecOpsArtifacts_throwsIllegalArgument() throws Exception {
        var spec = objectMapper.readTree("{\"business_goal\":\"test\"}");
        var ctx = MidasContext.start("Build an app", "run-001")
                .withTechnicalSpec(spec);

        assertThatThrownBy(() -> reducer.reduce(ctx, ContextReducer.AgentRole.CONTROLLER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("secOpsArtifacts");
    }

    @Test
    void reduce_withNullContext_throwsNullPointer() {
        assertThatThrownBy(() -> reducer.reduce(null, ContextReducer.AgentRole.SYSTEM_ANALYST))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void reduce_withNullRole_throwsNullPointer() {
        var ctx = MidasContext.start("idea", "run-001");
        assertThatThrownBy(() -> reducer.reduce(ctx, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void toCompactJson_systemAnalystView_isValidJson() throws Exception {
        var ctx = MidasContext.start("Build a todo app", "run-001");
        var view = reducer.reduce(ctx, ContextReducer.AgentRole.SYSTEM_ANALYST);
        String json = reducer.toCompactJson(view);

        assertThat(json).isNotBlank();
        var node = objectMapper.readTree(json);
        assertThat(node.get("agentName").asText()).isEqualTo("SYSTEM_ANALYST");
        assertThat(node.get("rawUserIdea").asText()).isEqualTo("Build a todo app");
    }

    @Test
    void toCompactJson_withNullView_throwsNullPointer() {
        assertThatThrownBy(() -> reducer.toCompactJson(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void estimatedTokenBudget_isPositive() {
        var ctx = MidasContext.start("Build a todo app with authentication", "run-001");
        var view = reducer.reduce(ctx, ContextReducer.AgentRole.SYSTEM_ANALYST);
        assertThat(view.getEstimatedTokenBudget()).isGreaterThan(0);
    }
}

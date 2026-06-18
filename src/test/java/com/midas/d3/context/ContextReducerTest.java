package com.midas.d3.context;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.midas.d3.agent.implementation.ImplementationSurface;
import com.midas.d3.config.JacksonConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

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
    void reduce_controller_withRequiredArtifacts_includesTechnicalSpecSecOpsAndManifest() throws Exception {
        var spec = objectMapper.readTree("{\"business_goal\":\"test\"}");
        var secOps = objectMapper.readTree("{\"release_artifacts\":{\"app.jar\":\"binary\"}}");
        var manifest = objectMapper.readTree("""
                [{"feature_id":"run-app","feature_name":"Run","files":["App.java"],"entry_points":["App"]}]
                """);
        var ctx = MidasContext.start("Build an app", "run-001")
                .withTechnicalSpec(spec)
                .withSecOpsArtifacts(secOps)
                .withFeatureManifest(manifest);

        var view = reducer.reduce(ctx, ContextReducer.AgentRole.CONTROLLER);

        assertThat(view.getAgentName()).isEqualTo("CONTROLLER");
        assertThat(view.safeArtifacts())
                .containsKeys("technicalSpec", "secOpsArtifacts", "featureManifest");
    }

    @Test
    void reduce_controller_missingFeatureManifest_throwsIllegalArgument() throws Exception {
        var spec = objectMapper.readTree("{\"business_goal\":\"test\"}");
        var secOps = objectMapper.readTree("{\"release_artifacts\":{}}");
        var ctx = MidasContext.start("Build an app", "run-001")
                .withTechnicalSpec(spec)
                .withSecOpsArtifacts(secOps);

        assertThatThrownBy(() -> reducer.reduce(ctx, ContextReducer.AgentRole.CONTROLLER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("featureManifest");
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
    void reduce_qaEngineer_doesNotIncludeFeatureManifest() throws Exception {
        var spec = objectMapper.readTree("{\"business_goal\":\"test\"}");
        var arch = objectMapper.readTree("{\"architecture_style\":\"SERVER_SIDE\"}");
        var source = objectMapper.readTree("{\"App.java\":\"public class App {}\"}");
        var manifest = objectMapper.readTree("""
                [{"feature_id":"run-app","feature_name":"Run","files":["App.java"],"entry_points":["App"]}]
                """);
        var ctx = MidasContext.start("Build an app", "run-001")
                .withTechnicalSpec(spec)
                .withArchitectureDesign(arch)
                .withGeneratedSourceCode(source)
                .withFeatureManifest(manifest);

        var view = reducer.reduce(ctx, ContextReducer.AgentRole.QA_ENGINEER);

        assertThat(view.safeArtifacts())
                .containsKeys("technicalSpec", "architectureDesign", "generatedSourceCode")
                .doesNotContainKey("featureManifest");
    }

    @Test
    void reduce_secOpsEngineer_doesNotIncludeFeatureManifest() throws Exception {
        var spec = objectMapper.readTree("{\"business_goal\":\"test\"}");
        var arch = objectMapper.readTree("{\"architecture_style\":\"SERVER_SIDE\"}");
        var source = objectMapper.readTree("{\"App.java\":\"public class App {}\"}");
        var tests = objectMapper.readTree("{\"AppTest.java\":\"class AppTest {}\"}");
        var manifest = objectMapper.readTree("""
                [{"feature_id":"run-app","feature_name":"Run","files":["App.java"],"entry_points":["App"]}]
                """);
        var ctx = MidasContext.start("Build an app", "run-001")
                .withTechnicalSpec(spec)
                .withArchitectureDesign(arch)
                .withGeneratedSourceCode(source)
                .withGeneratedTests(tests)
                .withFeatureManifest(manifest);

        var view = reducer.reduce(ctx, ContextReducer.AgentRole.SECOPS_ENGINEER);

        assertThat(view.safeArtifacts())
                .containsKeys("technicalSpec", "architectureDesign", "generatedSourceCode", "generatedTests")
                .doesNotContainKey("featureManifest");
    }

    @Test
    void reduce_implementationEngineer_withRemediationDirective_includesDirective() throws Exception {
        var spec = objectMapper.readTree("{\"business_goal\":\"test\"}");
        var arch = objectMapper.readTree("{\"architecture_style\":\"CLIENT_ONLY\"}");
        var directive = objectMapper.readTree("""
                {"source_verdict":"REJECT","required_changes":["Add login"],"coverage_gaps":[]}
                """);
        var ctx = MidasContext.start("Build an app", "run-001")
                .withTechnicalSpec(spec)
                .withArchitectureDesign(arch)
                .withRemediationDirective(directive);

        var view = reducer.reduce(ctx, ContextReducer.AgentRole.IMPLEMENTATION_ENGINEER);

        assertThat(view.safeArtifacts())
                .containsKey("remediationDirective")
                .containsEntry("remediationDirective", directive);
    }

    @Test
    void reduce_implementationEngineer_withoutRemediationDirective_omitsGracefully() throws Exception {
        var spec = objectMapper.readTree("{\"business_goal\":\"test\"}");
        var arch = objectMapper.readTree("{\"architecture_style\":\"CLIENT_ONLY\"}");
        var ctx = MidasContext.start("Build an app", "run-001")
                .withTechnicalSpec(spec)
                .withArchitectureDesign(arch);

        var view = reducer.reduce(ctx, ContextReducer.AgentRole.IMPLEMENTATION_ENGINEER);

        assertThat(view.safeArtifacts()).doesNotContainKey("remediationDirective");
    }

    @Test
    void reduce_qaEngineer_withRemediationDirective_includesDirective() throws Exception {
        var spec = objectMapper.readTree("{\"business_goal\":\"test\"}");
        var arch = objectMapper.readTree("{\"architecture_style\":\"SERVER_SIDE\"}");
        var source = objectMapper.readTree("{\"App.java\":\"public class App {}\"}");
        var directive = objectMapper.readTree("""
                {"source_verdict":"REJECT","required_changes":["Cover login flow"]}
                """);
        var ctx = MidasContext.start("Build an app", "run-001")
                .withTechnicalSpec(spec)
                .withArchitectureDesign(arch)
                .withGeneratedSourceCode(source)
                .withRemediationDirective(directive);

        var view = reducer.reduce(ctx, ContextReducer.AgentRole.QA_ENGINEER);

        assertThat(view.safeArtifacts()).containsKey("remediationDirective");
    }

    @Test
    void reduce_secOpsEngineer_withRemediationDirective_includesDirective() throws Exception {
        var spec = objectMapper.readTree("{\"business_goal\":\"test\"}");
        var arch = objectMapper.readTree("{\"architecture_style\":\"SERVER_SIDE\"}");
        var source = objectMapper.readTree("{\"App.java\":\"public class App {}\"}");
        var tests = objectMapper.readTree("{\"AppTest.java\":\"class AppTest {}\"}");
        var directive = objectMapper.readTree("""
                {"source_verdict":"REJECT","required_changes":["Harden auth endpoints"]}
                """);
        var ctx = MidasContext.start("Build an app", "run-001")
                .withTechnicalSpec(spec)
                .withArchitectureDesign(arch)
                .withGeneratedSourceCode(source)
                .withGeneratedTests(tests)
                .withRemediationDirective(directive);

        var view = reducer.reduce(ctx, ContextReducer.AgentRole.SECOPS_ENGINEER);

        assertThat(view.safeArtifacts()).containsKey("remediationDirective");
    }

    @Test
    void reduce_implementationPass_clientSlice_omitsServerFileLayout() throws Exception {
        var spec = objectMapper.readTree("""
                {"runtime_environment":{"execution_model":"HYBRID"}}
                """);
        var arch = objectMapper.readTree("""
                {
                  "architecture_style":"CLIENT_SERVER",
                  "components":[
                    {"name":"popup","type":"UI","responsibility":"UI"},
                    {"name":"Api","type":"CONTROLLER","responsibility":"API"}
                  ],
                  "file_layout":["manifest.json","src/main/java/com/example/App.java"],
                  "api_contracts":[{"method":"GET","path":"/api/items"}]
                }
                """);
        var ctx = MidasContext.start("Build hybrid", "run-001")
                .withTechnicalSpec(spec)
                .withArchitectureDesign(arch);

        var view = reducer.reduceImplementationPass(ctx, ImplementationSurface.CLIENT);

        assertThat(view.getAgentName()).isEqualTo("IMPLEMENTATION_ENGINEER_CLIENT");
        assertThat(view.safeArtifacts().get("architectureDesign").get("file_layout"))
                .hasSize(1);
        assertThat(view.safeArtifacts().get("architectureDesign").get("file_layout").get(0).asText())
                .isEqualTo("manifest.json");
        assertThat(view.safeArtifacts().get("architectureDesign").get("api_contracts"))
                .isEmpty();
    }

    @Test
    void reduce_implementationPass_withRemediationDirective_preservesDirective() throws Exception {
        var spec = objectMapper.readTree("""
                {"runtime_environment":{"execution_model":"HYBRID"}}
                """);
        var arch = objectMapper.readTree("""
                {"architecture_style":"CLIENT_SERVER","file_layout":["manifest.json","App.java"]}
                """);
        var directive = objectMapper.readTree("""
                {"source_verdict":"REJECT","required_changes":["Fix popup UX"]}
                """);
        var ctx = MidasContext.start("Build hybrid", "run-remediate")
                .withTechnicalSpec(spec)
                .withArchitectureDesign(arch)
                .withRemediationDirective(directive);

        var view = reducer.reduceImplementationPass(ctx, ImplementationSurface.CLIENT);

        assertThat(view.safeArtifacts()).containsKey("remediationDirective");
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

    // ── Patch reducer methods ────────────────────────────────────────────────

    @Test
    void reducePatchImplementationPass_includesFilteredBaselineSource() throws Exception {
        var spec = objectMapper.readTree("{\"business_goal\":\"test\"}");
        var arch = objectMapper.readTree("{\"architecture_style\":\"SERVER_SIDE\"}");
        var source = objectMapper.readTree("""
                {
                  "src/App.java": "public class App {}",
                  "src/Service.java": "public class Service {}",
                  "src/Util.java": "public class Util {}"
                }
                """);
        var directive = objectMapper.readTree("""
                {"source_verdict":"REJECT","remediation_mode":"SURGICAL_PATCH"}
                """);
        var ctx = MidasContext.start("Build an app", "run-patch-01")
                .withTechnicalSpec(spec)
                .withArchitectureDesign(arch)
                .withGeneratedSourceCode(source)
                .withRemediationDirective(directive);

        var view = reducer.reducePatchImplementationPass(ctx, List.of("src/App.java", "src/Service.java"));

        assertThat(view.getAgentName()).isEqualTo("IMPLEMENTATION_ENGINEER_PATCH");
        assertThat(view.safeArtifacts()).containsKeys("technicalSpec", "architectureDesign",
                "generatedSourceCode", "remediationDirective");
        JsonNode filteredSource = view.safeArtifacts().get("generatedSourceCode");
        assertThat(filteredSource.size()).isEqualTo(2);
        assertThat(filteredSource.has("src/App.java")).isTrue();
        assertThat(filteredSource.has("src/Service.java")).isTrue();
        assertThat(filteredSource.has("src/Util.java")).isFalse();
    }

    @Test
    void reducePatchImplementationPass_missingRequiredSpec_throws() throws Exception {
        var arch = objectMapper.readTree("{\"architecture_style\":\"SERVER_SIDE\"}");
        var ctx = MidasContext.start("Build an app", "run-patch-02")
                .withArchitectureDesign(arch);

        assertThatThrownBy(() -> reducer.reducePatchImplementationPass(ctx, List.of("src/App.java")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("technicalSpec");
    }

    @Test
    void reducePatchImplementationPass_noBaselineSource_omitsSourceArtifact() throws Exception {
        var spec = objectMapper.readTree("{\"business_goal\":\"test\"}");
        var arch = objectMapper.readTree("{\"architecture_style\":\"SERVER_SIDE\"}");
        var ctx = MidasContext.start("Build an app", "run-patch-03")
                .withTechnicalSpec(spec)
                .withArchitectureDesign(arch);

        var view = reducer.reducePatchImplementationPass(ctx, List.of("src/App.java"));

        assertThat(view.safeArtifacts()).doesNotContainKey("generatedSourceCode");
    }

    @Test
    void reducePatchTestPass_usesFilteredPatchedSource() throws Exception {
        var spec = objectMapper.readTree("{\"business_goal\":\"test\"}");
        var arch = objectMapper.readTree("{\"architecture_style\":\"SERVER_SIDE\"}");
        var patchedSource = objectMapper.readTree("""
                {
                  "src/App.java": "public class App { public static void main(String[] a) {} }",
                  "src/Service.java": "public class Service { public void run() {} }"
                }
                """);
        var ctx = MidasContext.start("Build an app", "run-patch-04")
                .withTechnicalSpec(spec)
                .withArchitectureDesign(arch);

        var view = reducer.reducePatchTestPass(ctx, List.of("src/App.java"), patchedSource);

        assertThat(view.getAgentName()).isEqualTo("QA_ENGINEER_PATCH");
        assertThat(view.safeArtifacts()).containsKeys("technicalSpec", "architectureDesign", "generatedSourceCode");
        JsonNode filteredSource = view.safeArtifacts().get("generatedSourceCode");
        assertThat(filteredSource.size()).isEqualTo(1);
        assertThat(filteredSource.has("src/App.java")).isTrue();
        assertThat(filteredSource.has("src/Service.java")).isFalse();
    }

    @Test
    void reducePatchTestPass_withRemediationDirective_preservesDirective() throws Exception {
        var spec = objectMapper.readTree("{\"business_goal\":\"test\"}");
        var arch = objectMapper.readTree("{\"architecture_style\":\"SERVER_SIDE\"}");
        var patchedSource = objectMapper.readTree("{\"src/App.java\":\"code\"}");
        var directive = objectMapper.readTree("{\"source_verdict\":\"REJECT\",\"remediation_mode\":\"SURGICAL_PATCH\"}");
        var ctx = MidasContext.start("Build an app", "run-patch-05")
                .withTechnicalSpec(spec)
                .withArchitectureDesign(arch)
                .withRemediationDirective(directive);

        var view = reducer.reducePatchTestPass(ctx, List.of("src/App.java"), patchedSource);

        assertThat(view.safeArtifacts()).containsKey("remediationDirective");
    }

    @Test
    void reducePatchTestPass_missingRequiredArch_throws() throws Exception {
        var spec = objectMapper.readTree("{\"business_goal\":\"test\"}");
        var patchedSource = objectMapper.readTree("{\"src/App.java\":\"code\"}");
        var ctx = MidasContext.start("Build an app", "run-patch-06")
                .withTechnicalSpec(spec);

        assertThatThrownBy(() -> reducer.reducePatchTestPass(ctx, List.of("src/App.java"), patchedSource))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("architectureDesign");
    }

    @Test
    void reducePatchSecOpsPass_usesFilteredPatchedSourceAndTests() throws Exception {
        var spec = objectMapper.readTree("{\"business_goal\":\"test\"}");
        var arch = objectMapper.readTree("{\"architecture_style\":\"SERVER_SIDE\"}");
        var patchedSource = objectMapper.readTree("""
                {
                  "src/App.java": "public class App {}",
                  "src/Util.java": "public class Util {}"
                }
                """);
        var patchedTests = objectMapper.readTree("""
                {
                  "src/AppTest.java": "class AppTest {}",
                  "src/UtilTest.java": "class UtilTest {}"
                }
                """);
        var ctx = MidasContext.start("Build an app", "run-patch-07")
                .withTechnicalSpec(spec)
                .withArchitectureDesign(arch);

        var view = reducer.reducePatchSecOpsPass(
                ctx,
                List.of("src/App.java", "src/AppTest.java"),
                patchedSource,
                patchedTests);

        assertThat(view.getAgentName()).isEqualTo("SECOPS_ENGINEER_PATCH");
        assertThat(view.safeArtifacts()).containsKeys(
                "technicalSpec", "architectureDesign", "generatedSourceCode", "generatedTests");

        JsonNode filteredSource = view.safeArtifacts().get("generatedSourceCode");
        assertThat(filteredSource.size()).isEqualTo(1);
        assertThat(filteredSource.has("src/App.java")).isTrue();
        assertThat(filteredSource.has("src/Util.java")).isFalse();

        JsonNode filteredTests = view.safeArtifacts().get("generatedTests");
        assertThat(filteredTests.size()).isEqualTo(1);
        assertThat(filteredTests.has("src/AppTest.java")).isTrue();
        assertThat(filteredTests.has("src/UtilTest.java")).isFalse();
    }

    @Test
    void reducePatchSecOpsPass_missingRequiredSpec_throws() throws Exception {
        var arch = objectMapper.readTree("{\"architecture_style\":\"SERVER_SIDE\"}");
        var source = objectMapper.readTree("{\"src/App.java\":\"code\"}");
        var tests = objectMapper.readTree("{\"src/AppTest.java\":\"test\"}");
        var ctx = MidasContext.start("Build an app", "run-patch-08")
                .withArchitectureDesign(arch);

        assertThatThrownBy(() -> reducer.reducePatchSecOpsPass(
                ctx, List.of("src/App.java"), source, tests))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("technicalSpec");
    }

    @Test
    void reducePatchSecOpsPass_withRemediationDirective_preservesDirective() throws Exception {
        var spec = objectMapper.readTree("{\"business_goal\":\"test\"}");
        var arch = objectMapper.readTree("{\"architecture_style\":\"SERVER_SIDE\"}");
        var source = objectMapper.readTree("{\"src/App.java\":\"code\"}");
        var tests = objectMapper.readTree("{\"src/AppTest.java\":\"test\"}");
        var directive = objectMapper.readTree("{\"source_verdict\":\"REJECT\",\"remediation_mode\":\"SURGICAL_PATCH\"}");
        var ctx = MidasContext.start("Build an app", "run-patch-09")
                .withTechnicalSpec(spec)
                .withArchitectureDesign(arch)
                .withRemediationDirective(directive);

        var view = reducer.reducePatchSecOpsPass(
                ctx, List.of("src/App.java"), source, tests);

        assertThat(view.safeArtifacts()).containsKey("remediationDirective");
        assertThat(view.safeArtifacts()).doesNotContainKey("featureManifest");
    }

    @Test
    void reducePatchImplementationPass_nullContext_throwsNullPointer() {
        assertThatThrownBy(() -> reducer.reducePatchImplementationPass(null, List.of("a")))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void reducePatchTestPass_nullPatchedSource_throwsNullPointer() throws Exception {
        var ctx = MidasContext.start("idea", "run-001");
        assertThatThrownBy(() -> reducer.reducePatchTestPass(ctx, List.of("a"), null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void reducePatchSecOpsPass_nullPatchedTests_throwsNullPointer() throws Exception {
        var ctx = MidasContext.start("idea", "run-001");
        var source = objectMapper.readTree("{\"src/App.java\":\"code\"}");
        assertThatThrownBy(() -> reducer.reducePatchSecOpsPass(ctx, List.of("a"), source, null))
                .isInstanceOf(NullPointerException.class);
    }
}

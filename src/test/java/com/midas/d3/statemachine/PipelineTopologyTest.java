package com.midas.d3.statemachine;

import com.midas.d3.context.MidasContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link PipelineTopology} — the single source of truth for pipeline routing.
 *
 * <p>These tests lock the invariants that both {@link PipelineStateMachineConfig} and
 * {@link com.midas.d3.statemachine.action.StoreArtifactAction} depend on, so the two routers
 * can never silently diverge.
 */
@DisplayName("PipelineTopology")
class PipelineTopologyTest {

    private final PipelineTopology topology = new PipelineTopology();

    @Test
    @DisplayName("first stage is SYSTEM_ANALYSIS")
    void firstStageIsSystemAnalysis() {
        assertThat(topology.firstStage()).isEqualTo(MidasState.SYSTEM_ANALYSIS);
    }

    @Test
    @DisplayName("processing stages are in the canonical happy-path order")
    void processingStagesAreInOrder() {
        assertThat(topology.processingStages()).containsExactly(
                MidasState.SYSTEM_ANALYSIS,
                MidasState.ARCHITECTURE_DESIGN,
                MidasState.INTEGRATION_STRATEGY,
                MidasState.CODE_GENERATION,
                MidasState.TEST_GENERATION,
                MidasState.SECOPS_AUDIT,
                MidasState.PRODUCT_REVIEW);
    }

    @Test
    @DisplayName("choice states align positionally with processing stages")
    void choiceStatesAlignWithProcessingStages() {
        assertThat(topology.choiceStates()).containsExactly(
                MidasState.ANALYSIS_CHOICE,
                MidasState.ARCHITECTURE_CHOICE,
                MidasState.INTEGRATION_CHOICE,
                MidasState.CODE_CHOICE,
                MidasState.TEST_CHOICE,
                MidasState.SECOPS_CHOICE,
                MidasState.PRODUCT_CHOICE);
    }

    @Test
    @DisplayName("each stage maps to its paired CHOICE pseudo-state")
    void choiceForEachStage() {
        assertThat(topology.choiceFor(MidasState.SYSTEM_ANALYSIS)).isEqualTo(MidasState.ANALYSIS_CHOICE);
        assertThat(topology.choiceFor(MidasState.ARCHITECTURE_DESIGN)).isEqualTo(MidasState.ARCHITECTURE_CHOICE);
        assertThat(topology.choiceFor(MidasState.INTEGRATION_STRATEGY)).isEqualTo(MidasState.INTEGRATION_CHOICE);
        assertThat(topology.choiceFor(MidasState.CODE_GENERATION)).isEqualTo(MidasState.CODE_CHOICE);
        assertThat(topology.choiceFor(MidasState.TEST_GENERATION)).isEqualTo(MidasState.TEST_CHOICE);
        assertThat(topology.choiceFor(MidasState.SECOPS_AUDIT)).isEqualTo(MidasState.SECOPS_CHOICE);
        assertThat(topology.choiceFor(MidasState.PRODUCT_REVIEW)).isEqualTo(MidasState.PRODUCT_CHOICE);
    }

    @Test
    @DisplayName("successor map mirrors the legacy linear routing exactly")
    void nextStageMatchesLegacyRouting() {
        assertThat(topology.nextStage(MidasState.SYSTEM_ANALYSIS)).isEqualTo(MidasState.ARCHITECTURE_DESIGN);
        assertThat(topology.nextStage(MidasState.ARCHITECTURE_DESIGN)).isEqualTo(MidasState.INTEGRATION_STRATEGY);
        assertThat(topology.nextStage(MidasState.INTEGRATION_STRATEGY)).isEqualTo(MidasState.CODE_GENERATION);
        assertThat(topology.nextStage(MidasState.CODE_GENERATION)).isEqualTo(MidasState.TEST_GENERATION);
        assertThat(topology.nextStage(MidasState.TEST_GENERATION)).isEqualTo(MidasState.SECOPS_AUDIT);
        assertThat(topology.nextStage(MidasState.SECOPS_AUDIT)).isEqualTo(MidasState.PRODUCT_REVIEW);
        assertThat(topology.nextStage(MidasState.PRODUCT_REVIEW)).isEqualTo(MidasState.COMPLETED);
    }

    @Test
    @DisplayName("context-aware routing skips INTEGRATION_STRATEGY when architecture declares no external integrations")
    void nextStageWithContext_skipsIntegrationWhenArchitectureFlagsFalse() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var spec = mapper.readTree("{\"business_goal\":\"CLI tool\"}");
        var arch = mapper.readTree("""
                {"architecture_style":"CLI_TOOL","has_external_integrations":false}
                """);
        var ctx = MidasContext.start("Build a CLI", "run-001")
                .withTechnicalSpec(spec)
                .withArchitectureDesign(arch);

        assertThat(topology.nextStage(MidasState.ARCHITECTURE_DESIGN, ctx))
                .isEqualTo(MidasState.CODE_GENERATION);
    }

    @Test
    @DisplayName("context-aware routing skips INTEGRATION_STRATEGY when technical spec declares no external integrations")
    void nextStageWithContext_skipsIntegrationWhenTechnicalSpecFlagsFalse() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var spec = mapper.readTree("""
                {"business_goal":"CLI tool","has_external_integrations":false}
                """);
        var arch = mapper.readTree("{\"architecture_style\":\"CLI_TOOL\"}");
        var ctx = MidasContext.start("Build a CLI", "run-001")
                .withTechnicalSpec(spec)
                .withArchitectureDesign(arch);

        assertThat(topology.nextStage(MidasState.ARCHITECTURE_DESIGN, ctx))
                .isEqualTo(MidasState.CODE_GENERATION);
    }

    @Test
    @DisplayName("context-aware routing keeps INTEGRATION_STRATEGY when no explicit skip flag is present")
    void nextStageWithContext_keepsIntegrationWhenFlagAbsent() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var spec = mapper.readTree("{\"business_goal\":\"SaaS app\"}");
        var arch = mapper.readTree("{\"architecture_style\":\"CLIENT_SERVER\"}");
        var ctx = MidasContext.start("Build SaaS", "run-001")
                .withTechnicalSpec(spec)
                .withArchitectureDesign(arch);

        assertThat(topology.nextStage(MidasState.ARCHITECTURE_DESIGN, ctx))
                .isEqualTo(MidasState.INTEGRATION_STRATEGY);
    }

    @Test
    @DisplayName("shouldSkipIntegrationStage reads validated architecture before it is stored on context")
    void shouldSkipIntegrationStage_usesValidatedArchitectureNode() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var validatedArch = mapper.readTree("""
                {"architecture_style":"CLIENT_ONLY","has_external_integrations":false}
                """);
        var ctx = MidasContext.start("Build a tool", "run-001");

        assertThat(topology.shouldSkipIntegrationStage(validatedArch, ctx)).isTrue();
    }

    @Test
    @DisplayName("only the last processing stage (PRODUCT_REVIEW) is final (successor = COMPLETED)")
    void onlyLastStageIsFinal() {
        assertThat(topology.isFinalStage(MidasState.PRODUCT_REVIEW)).isTrue();
        assertThat(topology.isFinalStage(MidasState.SECOPS_AUDIT)).isFalse();
        assertThat(topology.isFinalStage(MidasState.SYSTEM_ANALYSIS)).isFalse();
        assertThat(topology.isFinalStage(MidasState.CODE_GENERATION)).isFalse();
    }

    @Test
    @DisplayName("PRODUCT_REVIEW is the only blocking quality gate")
    void productReviewIsTheQualityGate() {
        assertThat(topology.isQualityGate(MidasState.PRODUCT_REVIEW)).isTrue();
        assertThat(topology.isQualityGate(MidasState.SECOPS_AUDIT)).isFalse();
        assertThat(topology.isQualityGate(MidasState.SYSTEM_ANALYSIS)).isFalse();
        assertThat(topology.isQualityGate(MidasState.COMPLETED)).isFalse();
        assertThat(topology.isQualityGate(null)).isFalse();
    }

    @Test
    @DisplayName("isProcessingStage distinguishes stages from CHOICE/terminal/null states")
    void isProcessingStageDiscriminates() {
        assertThat(topology.isProcessingStage(MidasState.CODE_GENERATION)).isTrue();
        assertThat(topology.isProcessingStage(MidasState.ANALYSIS_CHOICE)).isFalse();
        assertThat(topology.isProcessingStage(MidasState.COMPLETED)).isFalse();
        assertThat(topology.isProcessingStage(MidasState.IDLE)).isFalse();
        assertThat(topology.isProcessingStage(null)).isFalse();
    }

    @Test
    @DisplayName("routing accessors reject non-processing states")
    void routingAccessorsRejectNonProcessingStates() {
        assertThatThrownBy(() -> topology.nextStage(MidasState.COMPLETED))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> topology.choiceFor(MidasState.IDLE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> topology.nextStage(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("exposed collections are immutable defensive views")
    void exposedCollectionsAreImmutable() {
        assertThatThrownBy(() -> topology.processingStages().add(MidasState.IDLE))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> topology.choiceStates().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }
}

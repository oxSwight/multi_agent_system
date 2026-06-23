package com.midas.d3.llm;

import com.midas.d3.statemachine.MidasState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("LlmModelPolicy Tests")
class LlmModelPolicyTest {

    private static final String DEFAULT_MODEL = "gemini-2.5-flash";
    private static final String STAGE_MODEL = "gemini-2.5-flash";

    private LlmModelPolicy policy;

    @BeforeEach
    void setUp() {
        LlmModelPolicyProperties properties = new LlmModelPolicyProperties();
        properties.setModel(DEFAULT_MODEL);
        properties.setStageModels(Map.of(
                MidasState.CODE_GENERATION.name(), STAGE_MODEL,
                MidasState.TEST_GENERATION.name(), STAGE_MODEL));
        policy = new LlmModelPolicy(properties);
    }

    @Test
    @DisplayName("CODE_GENERATION resolves to configured Flash model")
    void resolve_codeGeneration_returnsStageModel() {
        assertThat(policy.resolve(MidasState.CODE_GENERATION)).isEqualTo(STAGE_MODEL);
    }

    @Test
    @DisplayName("TEST_GENERATION resolves to configured Flash model")
    void resolve_testGeneration_returnsStageModel() {
        assertThat(policy.resolve(MidasState.TEST_GENERATION)).isEqualTo(STAGE_MODEL);
    }

    @ParameterizedTest
    @EnumSource(value = MidasState.class, names = {
            "SYSTEM_ANALYSIS",
            "ARCHITECTURE_DESIGN",
            "INTEGRATION_STRATEGY",
            "SECOPS_AUDIT",
            "PRODUCT_REVIEW",
            "IDLE",
            "WAITING_FOR_USER_INPUT",
            "COMPLETED",
            "ERROR",
            "ANALYSIS_CHOICE",
            "ARCHITECTURE_CHOICE",
            "INTEGRATION_CHOICE",
            "CODE_CHOICE",
            "TEST_CHOICE",
            "SECOPS_CHOICE",
            "PRODUCT_CHOICE"
    })
    @DisplayName("Unmapped stages fall back to default Flash model")
    void resolve_unmappedStage_returnsDefaultModel(MidasState stage) {
        assertThat(policy.resolve(stage)).isEqualTo(DEFAULT_MODEL);
    }

    @Test
    @DisplayName("Null stage is rejected")
    void resolve_nullStage_throws() {
        assertThatThrownBy(() -> policy.resolve(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Blank stage override falls back to default model")
    void resolve_blankStageOverride_returnsDefaultModel() {
        LlmModelPolicyProperties properties = new LlmModelPolicyProperties();
        properties.setModel(DEFAULT_MODEL);
        properties.setStageModels(Map.of(MidasState.CODE_GENERATION.name(), "   "));
        LlmModelPolicy blankOverridePolicy = new LlmModelPolicy(properties);

        assertThat(blankOverridePolicy.resolve(MidasState.CODE_GENERATION)).isEqualTo(DEFAULT_MODEL);
    }

    // ── Two-tier (fast model) policy ───────────────────────────────────────────

    private static final String PRIMARY = "primary-14b";
    private static final String FAST = "fast-1b";

    private LlmModelPolicy fastTierPolicy(String fastModel, Set<String> fastStages, Map<String, String> pins) {
        LlmModelPolicyProperties p = new LlmModelPolicyProperties();
        p.setModel(PRIMARY);
        p.setFastModel(fastModel);
        p.setFastStages(fastStages);
        p.setStageModels(pins);
        return new LlmModelPolicy(p);
    }

    @Test
    @DisplayName("Fast tier active: a fast-eligible stage resolves to the fast model")
    void resolve_fastStage_returnsFastModel() {
        LlmModelPolicy p = fastTierPolicy(FAST, Set.of(), Map.of());
        assertThat(p.resolve(MidasState.INTEGRATION_STRATEGY)).isEqualTo(FAST);
    }

    @Test
    @DisplayName("Fast tier active: heavy generation and critical gates stay on the primary model")
    void resolve_heavyAndGateStages_stayPrimary() {
        LlmModelPolicy p = fastTierPolicy(FAST, Set.of(), Map.of());
        assertThat(p.resolve(MidasState.SYSTEM_ANALYSIS)).isEqualTo(PRIMARY);
        assertThat(p.resolve(MidasState.CODE_GENERATION)).isEqualTo(PRIMARY);
        assertThat(p.resolve(MidasState.SECOPS_AUDIT)).isEqualTo(PRIMARY);
        assertThat(p.resolve(MidasState.PRODUCT_REVIEW)).isEqualTo(PRIMARY);
    }

    @Test
    @DisplayName("Fast tier inactive (no fast-model): a fast-eligible stage falls back to primary")
    void resolve_fastTierInactive_returnsPrimary() {
        LlmModelPolicy p = fastTierPolicy("", Set.of(), Map.of());
        assertThat(p.isFastTierActive()).isFalse();
        assertThat(p.resolve(MidasState.INTEGRATION_STRATEGY)).isEqualTo(PRIMARY);
    }

    @Test
    @DisplayName("Explicit stage pin beats the fast tier")
    void resolve_explicitPin_beatsFastTier() {
        LlmModelPolicy p = fastTierPolicy(FAST, Set.of(),
                Map.of(MidasState.INTEGRATION_STRATEGY.name(), "pinned-7b"));
        assertThat(p.resolve(MidasState.INTEGRATION_STRATEGY)).isEqualTo("pinned-7b");
    }

    @Test
    @DisplayName("Configured fast-stages override the built-in default classification")
    void resolve_customFastStages_override() {
        LlmModelPolicy p = fastTierPolicy(FAST, Set.of(MidasState.ARCHITECTURE_DESIGN.name()), Map.of());
        assertThat(p.resolve(MidasState.ARCHITECTURE_DESIGN)).isEqualTo(FAST);
        // Once an explicit set is configured, the default INTEGRATION_STRATEGY is no longer fast.
        assertThat(p.resolve(MidasState.INTEGRATION_STRATEGY)).isEqualTo(PRIMARY);
    }

    @Test
    @DisplayName("Unknown fast-stage names are ignored, falling back to the default classification")
    void resolve_unknownFastStage_ignored() {
        LlmModelPolicy p = fastTierPolicy(FAST, Set.of("NOT_A_REAL_STAGE"), Map.of());
        assertThat(p.resolve(MidasState.INTEGRATION_STRATEGY)).isEqualTo(FAST);
    }

    @Test
    @DisplayName("Tier accessors expose primary/fast model and stage classification")
    void accessors_exposeTierMetadata() {
        LlmModelPolicy p = fastTierPolicy(FAST, Set.of(), Map.of());
        assertThat(p.primaryModel()).isEqualTo(PRIMARY);
        assertThat(p.fastModel()).isEqualTo(FAST);
        assertThat(p.isFastStage(MidasState.INTEGRATION_STRATEGY)).isTrue();
        assertThat(p.isFastStage(MidasState.CODE_GENERATION)).isFalse();
    }
}

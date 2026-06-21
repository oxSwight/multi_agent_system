package com.midas.d3.llm;

import com.midas.d3.statemachine.MidasState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Map;

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
}

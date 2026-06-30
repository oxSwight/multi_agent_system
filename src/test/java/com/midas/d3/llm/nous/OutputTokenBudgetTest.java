package com.midas.d3.llm.nous;

import com.midas.d3.statemachine.MidasState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("OutputTokenBudget")
class OutputTokenBudgetTest {

    private NousProperties.OutputBudget cfg() {
        return new NousProperties.OutputBudget();
    }

    @Test
    @DisplayName("Bounded stages get their trimmed built-in cap")
    void boundedStages_useBuiltinCap() {
        assertThat(OutputTokenBudget.resolve(MidasState.PRODUCT_REVIEW, "qwen2.5-coder:14b", cfg())).isEqualTo(3_072);
        assertThat(OutputTokenBudget.resolve(MidasState.INTEGRATION_STRATEGY, "qwen2.5-coder:14b", cfg())).isEqualTo(3_072);
        assertThat(OutputTokenBudget.resolve(MidasState.SECOPS_AUDIT, "qwen2.5-coder:14b", cfg())).isEqualTo(6_144);
    }

    @Test
    @DisplayName("Heavy generation stages keep the full default budget")
    void heavyStages_useDefault() {
        assertThat(OutputTokenBudget.resolve(MidasState.CODE_GENERATION, "qwen2.5-coder:14b", cfg())).isEqualTo(8_192);
        assertThat(OutputTokenBudget.resolve(MidasState.SYSTEM_ANALYSIS, "qwen2.5-coder:14b", cfg())).isEqualTo(8_192);
    }

    @Test
    @DisplayName("Operator per-stage override wins over the built-in cap")
    void operatorOverride_wins() {
        NousProperties.OutputBudget cfg = cfg();
        cfg.getStages().put(MidasState.PRODUCT_REVIEW.name(), 512);
        assertThat(OutputTokenBudget.resolve(MidasState.PRODUCT_REVIEW, "qwen2.5-coder:14b", cfg)).isEqualTo(512);
    }

    @Test
    @DisplayName("Reasoning model floor overrides a smaller per-stage cap")
    void reasoningFloor_overridesSmallCap() {
        // PRODUCT_REVIEW built-in is 3072, below the reasoning floor → floor wins for deepseek-r1.
        assertThat(OutputTokenBudget.resolve(MidasState.PRODUCT_REVIEW, "deepseek-r1:8b", cfg()))
                .isEqualTo(OutputTokenBudget.REASONING_FLOOR);
    }

    @Test
    @DisplayName("Reasoning floor never lowers an already-large budget")
    void reasoningFloor_doesNotLower() {
        // CODE_GENERATION default 8192 is below the 16384 floor, so the floor raises it; but a
        // hypothetical larger default must not be lowered.
        NousProperties.OutputBudget cfg = cfg();
        cfg.setDefaultTokens(32_768);
        assertThat(OutputTokenBudget.resolve(MidasState.CODE_GENERATION, "deepseek-r1:8b", cfg)).isEqualTo(32_768);
    }
}

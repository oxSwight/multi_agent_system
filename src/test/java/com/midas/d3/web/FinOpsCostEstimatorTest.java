package com.midas.d3.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("FinOpsCostEstimator Tests")
class FinOpsCostEstimatorTest {

    private FinOpsCostEstimator estimator;

    @BeforeEach
    void setUp() {
        estimator = new FinOpsCostEstimator(0.075, 0.30, List.of("qwen", "llama", "ollama"));
    }

    @Test
    @DisplayName("zero tokens → null (no cost recorded)")
    void zeroTokens_returnsNull() {
        assertThat(estimator.estimateCostUsd(0, 0, "gemini-2.5-flash")).isNull();
        assertThat(estimator.estimateCostUsd(0, 0)).isNull();
    }

    @Test
    @DisplayName("local/free model → $0 even with tokens (default deployment runs local qwen)")
    void freeModel_returnsZero() {
        assertThat(estimator.estimateCostUsd(1_000_000, 1_000_000, "qwen2.5-coder:14b")).isEqualTo(0.0);
    }

    @Test
    @DisplayName("cloud model → configured cloud rate")
    void cloudModel_usesConfiguredRate() {
        // 1M prompt * 0.075 + 1M completion * 0.30 = 0.375
        assertThat(estimator.estimateCostUsd(1_000_000, 1_000_000, "gemini-2.5-flash")).isEqualTo(0.375);
    }

    @Test
    @DisplayName("null/unknown model → default cloud rate (backward-compatible)")
    void unknownModel_usesDefaultRate() {
        assertThat(estimator.estimateCostUsd(1_000_000, 0, null)).isEqualTo(0.075);
        assertThat(estimator.estimateCostUsd(1_000_000, 0)).isEqualTo(0.075);
    }

    @Test
    @DisplayName("free-model match is case-insensitive and prefix-based")
    void freeModelMatch_caseInsensitivePrefix() {
        assertThat(estimator.estimateCostUsd(1_000, 1_000, "QWEN2.5-Coder")).isEqualTo(0.0);
        assertThat(estimator.estimateCostUsd(1_000, 1_000, "Llama-3.1-70b")).isEqualTo(0.0);
    }
}

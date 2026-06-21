package com.midas.d3.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Estimates LLM cost from token counts using configurable per-million-token rates.
 */
@Component
public class FinOpsCostEstimator {

    private final double usdPerMillionPromptTokens;
    private final double usdPerMillionCompletionTokens;

    public FinOpsCostEstimator(
            @Value("${midas.finops.usd-per-million-prompt-tokens:0.075}") double usdPerMillionPromptTokens,
            @Value("${midas.finops.usd-per-million-completion-tokens:0.30}") double usdPerMillionCompletionTokens) {
        this.usdPerMillionPromptTokens = usdPerMillionPromptTokens;
        this.usdPerMillionCompletionTokens = usdPerMillionCompletionTokens;
    }

    public Double estimateCostUsd(int promptTokens, int completionTokens) {
        if (promptTokens == 0 && completionTokens == 0) {
            return null;
        }
        double cost = (promptTokens * usdPerMillionPromptTokens
                + completionTokens * usdPerMillionCompletionTokens) / 1_000_000.0;
        return Math.round(cost * 1_000_000.0) / 1_000_000.0;
    }
}

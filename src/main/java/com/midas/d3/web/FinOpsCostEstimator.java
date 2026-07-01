package com.midas.d3.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * Estimates LLM cost from token counts using configurable per-million-token rates.
 *
 * <p><b>Model awareness.</b> Locally-hosted / self-hosted models (Ollama qwen, llama, etc.) have no
 * per-token cost, so applying the cloud list price to them badly overstates unit economics — the default
 * deployment runs local qwen. {@link #estimateCostUsd(int, int, String)} therefore charges $0 for models
 * whose id starts with a configured free-model prefix, and the configured cloud rate otherwise.
 */
@Component
public class FinOpsCostEstimator {

    private final double usdPerMillionPromptTokens;
    private final double usdPerMillionCompletionTokens;
    private final List<String> freeModelPrefixes;

    public FinOpsCostEstimator(
            @Value("${midas.finops.usd-per-million-prompt-tokens:0.075}") double usdPerMillionPromptTokens,
            @Value("${midas.finops.usd-per-million-completion-tokens:0.30}") double usdPerMillionCompletionTokens,
            @Value("${midas.finops.free-model-prefixes:qwen,llama,codellama,deepseek,mistral,mixtral,phi,gemma,ollama}")
            List<String> freeModelPrefixes) {
        this.usdPerMillionPromptTokens = usdPerMillionPromptTokens;
        this.usdPerMillionCompletionTokens = usdPerMillionCompletionTokens;
        this.freeModelPrefixes = freeModelPrefixes.stream()
                .map(s -> s.trim().toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /**
     * Model-agnostic estimate using the configured cloud rate. Prefer
     * {@link #estimateCostUsd(int, int, String)} so locally-hosted models are correctly costed at $0.
     */
    public Double estimateCostUsd(int promptTokens, int completionTokens) {
        return estimateCostUsd(promptTokens, completionTokens, null);
    }

    /**
     * Model-aware estimate. Returns {@code null} when no tokens were recorded, {@code 0.0} for a
     * locally-hosted / free model, and the configured cloud rate otherwise.
     */
    public Double estimateCostUsd(int promptTokens, int completionTokens, String modelId) {
        if (promptTokens == 0 && completionTokens == 0) {
            return null;
        }
        if (isFreeModel(modelId)) {
            return 0.0;
        }
        double cost = (promptTokens * usdPerMillionPromptTokens
                + completionTokens * usdPerMillionCompletionTokens) / 1_000_000.0;
        return Math.round(cost * 1_000_000.0) / 1_000_000.0;
    }

    private boolean isFreeModel(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            return false;
        }
        String normalized = modelId.trim().toLowerCase(Locale.ROOT);
        return freeModelPrefixes.stream().anyMatch(normalized::startsWith);
    }
}

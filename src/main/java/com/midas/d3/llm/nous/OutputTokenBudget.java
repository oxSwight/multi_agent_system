package com.midas.d3.llm.nous;

import com.midas.d3.statemachine.MidasState;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * Resolves the {@code max_tokens} completion budget for an LLM call from its pipeline stage.
 *
 * <h2>Why per-stage (FinOps)</h2>
 * A single flat cap made every stage reserve a code-generation-sized completion budget, even the
 * bounded ones (the Controller emits a one-word verdict or a small JSON; the integration stage is
 * thin). Per-stage caps bound the worst-case completion of cheap stages without touching the heavy
 * generation stages.
 *
 * <h2>Conservative by design</h2>
 * Only clearly-bounded stages are trimmed; analysis, architecture, code, and test generation keep
 * the full default. A cap that truncates legitimate output would trigger a MAX_TOKENS failure and a
 * retry — spending <em>more</em> tokens — so the trimmed values stay generous. Reasoning models
 * (DeepSeek-R1) fill a {@code reasoning} field before content, so a reasoning floor overrides any
 * smaller per-stage cap.
 */
final class OutputTokenBudget {

    /** Reasoning models need a large budget regardless of stage — they emit reasoning before content. */
    static final int REASONING_FLOOR = 16_384;

    /** Built-in caps for the bounded stages. Stages absent here use {@link NousProperties.OutputBudget#getDefaultTokens()}. */
    private static final Map<MidasState, Integer> BUILTIN = new EnumMap<>(MidasState.class);

    static {
        BUILTIN.put(MidasState.INTEGRATION_STRATEGY, 3_072); // thin, skip-eligible, structured
        BUILTIN.put(MidasState.PRODUCT_REVIEW, 3_072);       // verdict + small coverage matrix
        BUILTIN.put(MidasState.SECOPS_AUDIT, 6_144);         // markdown audit + release artifacts
        // SYSTEM_ANALYSIS / ARCHITECTURE_DESIGN / CODE_GENERATION / TEST_GENERATION → default (heavy).
    }

    private OutputTokenBudget() {
    }

    static int resolve(MidasState stage, String model, NousProperties.OutputBudget config) {
        int budget = config.getDefaultTokens();
        if (stage != null && BUILTIN.containsKey(stage)) {
            budget = BUILTIN.get(stage);
        }
        if (stage != null && config.getStages() != null) {
            Integer override = config.getStages().get(stage.name());
            if (override != null && override > 0) {
                budget = override; // operator override wins over the built-in default
            }
        }
        if (isReasoningModel(model)) {
            budget = Math.max(budget, REASONING_FLOOR);
        }
        return budget;
    }

    private static boolean isReasoningModel(String model) {
        if (model == null) {
            return false;
        }
        String lower = model.toLowerCase(Locale.ROOT);
        return lower.contains("deepseek-r1") || lower.contains("r1:");
    }
}

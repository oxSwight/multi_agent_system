package com.midas.d3.llm;

import com.midas.d3.statemachine.MidasState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Resolves which model a pipeline stage should run on, implementing a two-tier policy:
 *
 * <ol>
 *   <li><b>Explicit pin</b> — {@code midas.llm.stage-models[STAGE]} always wins (operator override).</li>
 *   <li><b>Fast tier</b> — when a {@code fast-model} is configured and the stage is fast-eligible,
 *       the stage drops to the cheap/fast model. Tier-down is opt-in: with no {@code fast-model}
 *       configured this branch never fires and behavior is identical to the single-tier policy.</li>
 *   <li><b>Primary tier</b> — otherwise the heavy-reasoning {@code model}.</li>
 * </ol>
 *
 * <p>The fast tier is deliberately conservative. Heavy generation (analysis, architecture, code,
 * tests) and — critically — the blocking quality gates (SecOps audit, Product Review) stay on the
 * primary model: cheapening a gate that decides correctness is a false economy, the same failure
 * mode flagged in the multi-model-council assessment. By default only
 * {@link MidasState#INTEGRATION_STRATEGY} (a thin, skip-eligible, structured stage) is fast-eligible.
 */
@Slf4j
@Component
public class LlmModelPolicy {

    /**
     * Conservative built-in fast-tier classification used when {@code midas.llm.fast-stages} is not
     * configured. Only the integration-strategy stage — thin, skip-eligible, and not a correctness
     * gate — qualifies out of the box.
     */
    static final Set<MidasState> DEFAULT_FAST_STAGES = EnumSet.of(MidasState.INTEGRATION_STRATEGY);

    private final LlmModelPolicyProperties properties;

    public LlmModelPolicy(LlmModelPolicyProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
    }

    /**
     * Resolves the model id for a stage following the explicit-pin → fast-tier → primary precedence.
     *
     * @param stage pipeline stage; must not be null
     * @return the resolved model id (never blank)
     */
    public String resolve(MidasState stage) {
        Objects.requireNonNull(stage, "stage must not be null");

        // 1. Explicit operator pin — highest precedence.
        String pinned = properties.getStageModels().get(stage.name());
        if (pinned != null && !pinned.isBlank()) {
            return pinned.trim();
        }

        // 2. Fast tier — only when a fast model is configured and the stage is fast-eligible.
        if (isFastTierActive() && fastStages().contains(stage)) {
            String fast = properties.getFastModel().trim();
            log.debug("LlmModelPolicy → stage={} routed to FAST tier model={}", stage, fast);
            return fast;
        }

        // 3. Primary tier.
        return properties.getModel();
    }

    /** True when a non-blank fast model is configured (i.e. tier-down is enabled). */
    public boolean isFastTierActive() {
        String fast = properties.getFastModel();
        return fast != null && !fast.isBlank();
    }

    /** The effective fast model, or the primary model when the fast tier is not configured. */
    public String fastModel() {
        return isFastTierActive() ? properties.getFastModel().trim() : properties.getModel();
    }

    /** The primary (heavy-reasoning) model. */
    public String primaryModel() {
        return properties.getModel();
    }

    /** Whether the given stage is classified as fast-tier-eligible (independent of activation). */
    public boolean isFastStage(MidasState stage) {
        return fastStages().contains(Objects.requireNonNull(stage, "stage must not be null"));
    }

    /**
     * The active fast-stage set: the operator-configured {@code fast-stages} when present and
     * parseable, otherwise the built-in {@link #DEFAULT_FAST_STAGES}. Unknown stage names are
     * skipped with a warning rather than failing startup.
     */
    private Set<MidasState> fastStages() {
        Set<String> configured = properties.getFastStages();
        if (configured == null || configured.isEmpty()) {
            return DEFAULT_FAST_STAGES;
        }
        EnumSet<MidasState> resolved = EnumSet.noneOf(MidasState.class);
        for (String name : configured) {
            if (name == null || name.isBlank()) {
                continue;
            }
            try {
                resolved.add(MidasState.valueOf(name.trim()));
            } catch (IllegalArgumentException e) {
                log.warn("LlmModelPolicy → ignoring unknown fast-stage [{}] in midas.llm.fast-stages", name);
            }
        }
        return resolved.isEmpty() ? DEFAULT_FAST_STAGES : resolved;
    }

    // ── Escalation tier (F5) ─────────────────────────────────────────────────

    /** Conservative built-in escalation-eligible set: the heavy generation stages. */
    static final Set<MidasState> DEFAULT_ESCALATION_STAGES = EnumSet.of(
            MidasState.SYSTEM_ANALYSIS,
            MidasState.ARCHITECTURE_DESIGN,
            MidasState.CODE_GENERATION,
            MidasState.TEST_GENERATION);

    /** True when a non-blank escalation model is configured (i.e. escalation is enabled). */
    public boolean isEscalationTierActive() {
        String esc = properties.getEscalationModel();
        return esc != null && !esc.isBlank();
    }

    /** The configured escalation model, or the primary model when escalation is not configured. */
    public String escalationModel() {
        return isEscalationTierActive() ? properties.getEscalationModel().trim() : properties.getModel();
    }

    /** Whether the given stage is classified as escalation-eligible (independent of activation). */
    public boolean isEscalationStage(MidasState stage) {
        return escalationStages().contains(Objects.requireNonNull(stage, "stage must not be null"));
    }

    /**
     * Resolves the model for a specific retry attempt. Identical to {@link #resolve(MidasState)} except
     * that the FINAL attempt of an escalation-eligible, non-pinned stage routes to the configured
     * escalation (stronger) model — a deliberate, bounded last resort after the primary tier has failed
     * every earlier attempt. An explicit operator pin always wins over escalation.
     *
     * @param stage       pipeline stage; must not be null
     * @param attempt     1-based attempt index
     * @param maxAttempts total attempts for this stage (escalation fires only when {@code attempt == maxAttempts})
     */
    public String resolveForAttempt(MidasState stage, int attempt, int maxAttempts) {
        String base = resolve(stage);
        boolean finalAttempt = attempt >= maxAttempts;
        if (!finalAttempt || isPinned(stage) || !isEscalationTierActive() || !escalationStages().contains(stage)) {
            return base;
        }
        String escalated = properties.getEscalationModel().trim();
        if (!escalated.equals(base)) {
            log.info("LlmModelPolicy → stage={} FINAL attempt {}/{} ESCALATED to model={}",
                    stage, attempt, maxAttempts, escalated);
        }
        return escalated;
    }

    /** True when an operator has explicitly pinned a model for this stage. */
    private boolean isPinned(MidasState stage) {
        String pinned = properties.getStageModels().get(stage.name());
        return pinned != null && !pinned.isBlank();
    }

    /**
     * The active escalation-stage set: the operator-configured {@code escalation-stages} when present
     * and parseable, otherwise the built-in {@link #DEFAULT_ESCALATION_STAGES}. Unknown stage names are
     * skipped with a warning rather than failing startup.
     */
    private Set<MidasState> escalationStages() {
        Set<String> configured = properties.getEscalationStages();
        if (configured == null || configured.isEmpty()) {
            return DEFAULT_ESCALATION_STAGES;
        }
        EnumSet<MidasState> resolved = EnumSet.noneOf(MidasState.class);
        for (String name : configured) {
            if (name == null || name.isBlank()) {
                continue;
            }
            try {
                resolved.add(MidasState.valueOf(name.trim()));
            } catch (IllegalArgumentException e) {
                log.warn("LlmModelPolicy → ignoring unknown escalation-stage [{}] in midas.llm.escalation-stages", name);
            }
        }
        return resolved.isEmpty() ? DEFAULT_ESCALATION_STAGES : resolved;
    }
}

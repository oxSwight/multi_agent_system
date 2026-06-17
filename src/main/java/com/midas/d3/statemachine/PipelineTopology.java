package com.midas.d3.statemachine;

import com.fasterxml.jackson.databind.JsonNode;
import com.midas.d3.context.MidasContext;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

/**
 * Single source of truth for the MIDAS pipeline's processing-stage ordering and routing.
 *
 * <p><b>Why this exists.</b> Before this resolver the "what stage comes next" relationship
 * was declared in two independent places that had to be kept in sync by hand:
 * <ul>
 *   <li>{@link PipelineStateMachineConfig} — the {@code .first(MidasState.X, …)} CHOICE targets;</li>
 *   <li>{@code StoreArtifactAction.nextStageAfter()} — a parallel hardcoded {@code switch}.</li>
 * </ul>
 * Any change to the pipeline shape (inserting an agent, skipping a stage, a backward
 * feedback loop) required edits to both, with silent-divergence risk. This class makes the
 * topology declarative and authoritative: every router consults it, so a shape change is a
 * single edit here.
 *
 * <p><b>Single declaration point.</b> The linear order is declared exactly once in
 * {@link #PROCESSING_ORDER}. The successor map is <em>derived</em> from that order (the last
 * processing stage flows to {@link MidasState#COMPLETED}); the only other hand-maintained
 * fact is the 1:1 pairing of each processing stage with its CHOICE pseudo-state.
 *
 * <p><b>Routing-ready by design.</b> The accessors are intentionally shaped so that a future
 * dynamic router (e.g. skip {@code INTEGRATION_STRATEGY} for self-contained tools, or insert a
 * terminal {@code PRODUCT_REVIEW} gate) can be layered on top without changing call sites:
 * the state-machine config and the artifact-store action already ask <em>this</em> object
 * "what is next", rather than embedding the answer themselves.
 *
 * <p>The instance is immutable and stateless; all maps are computed once in the constructor and
 * exposed only through defensive, unmodifiable views.
 */
@Component
public class PipelineTopology {

    /**
     * The canonical happy-path order of processing stages. This is the ONLY place the linear
     * sequence is declared; the successor relationship and the CHOICE-state list are derived
     * from it. Reorder, insert, or remove a stage here and every router follows automatically.
     */
    private static final List<MidasState> PROCESSING_ORDER = List.of(
            MidasState.SYSTEM_ANALYSIS,
            MidasState.ARCHITECTURE_DESIGN,
            MidasState.INTEGRATION_STRATEGY,
            MidasState.CODE_GENERATION,
            MidasState.TEST_GENERATION,
            MidasState.SECOPS_AUDIT,
            // Phase 3: blocking Product-Owner quality gate, inserted immediately after the
            // SecOps audit and flowing to COMPLETED on a passing verdict (derived successor).
            MidasState.PRODUCT_REVIEW);

    /**
     * Processing stages that act as blocking quality gates: their CHOICE routing additionally
     * branches on the agent's verdict, sending a REJECT to {@link MidasState#ERROR} rather than
     * advancing. Declared here (not in the state-machine config) so the topology stays the single
     * source of truth for the pipeline's routing shape.
     */
    private static final EnumSet<MidasState> QUALITY_GATES =
            EnumSet.of(MidasState.PRODUCT_REVIEW);

    private final List<MidasState> processingStages;
    private final List<MidasState> choiceStates;
    private final EnumMap<MidasState, MidasState> choiceByStage;
    private final EnumMap<MidasState, MidasState> nextByStage;

    public PipelineTopology() {
        this.processingStages = List.copyOf(PROCESSING_ORDER);

        // The one hand-maintained fact besides the order: each stage's paired CHOICE node.
        this.choiceByStage = new EnumMap<>(MidasState.class);
        choiceByStage.put(MidasState.SYSTEM_ANALYSIS,      MidasState.ANALYSIS_CHOICE);
        choiceByStage.put(MidasState.ARCHITECTURE_DESIGN,  MidasState.ARCHITECTURE_CHOICE);
        choiceByStage.put(MidasState.INTEGRATION_STRATEGY, MidasState.INTEGRATION_CHOICE);
        choiceByStage.put(MidasState.CODE_GENERATION,      MidasState.CODE_CHOICE);
        choiceByStage.put(MidasState.TEST_GENERATION,      MidasState.TEST_CHOICE);
        choiceByStage.put(MidasState.SECOPS_AUDIT,         MidasState.SECOPS_CHOICE);
        choiceByStage.put(MidasState.PRODUCT_REVIEW,       MidasState.PRODUCT_CHOICE);

        // Fail fast on a misconfigured topology rather than at runtime mid-pipeline.
        for (MidasState stage : processingStages) {
            if (!choiceByStage.containsKey(stage)) {
                throw new IllegalStateException(
                        "PipelineTopology misconfigured: no CHOICE pseudo-state paired with processing stage "
                                + stage + ". Every processing stage must declare a CHOICE node.");
            }
        }

        // Successor map is DERIVED from the order: stage[i] → stage[i+1], last → COMPLETED.
        this.nextByStage = new EnumMap<>(MidasState.class);
        for (int i = 0; i < processingStages.size(); i++) {
            MidasState current = processingStages.get(i);
            MidasState next = (i + 1 < processingStages.size())
                    ? processingStages.get(i + 1)
                    : MidasState.COMPLETED;
            nextByStage.put(current, next);
        }

        this.choiceStates = processingStages.stream()
                .map(choiceByStage::get)
                .toList();
    }

    // ── Accessors ──────────────────────────────────────────────────────────────

    /** The first processing stage entered when a pipeline starts (IDLE → here on START). */
    public MidasState firstStage() {
        return processingStages.get(0);
    }

    /** Ordered, immutable list of all processing stages in happy-path sequence. */
    public List<MidasState> processingStages() {
        return Collections.unmodifiableList(processingStages);
    }

    /** Ordered, immutable list of CHOICE pseudo-states, aligned with {@link #processingStages()}. */
    public List<MidasState> choiceStates() {
        return Collections.unmodifiableList(choiceStates);
    }

    /** {@code true} iff {@code state} is a processing stage participating in the pipeline routing. */
    public boolean isProcessingStage(MidasState state) {
        return state != null && choiceByStage.containsKey(state);
    }

    /**
     * The CHOICE pseudo-state that {@code stage} routes into on {@code SUBMIT_RESULT}.
     *
     * @throws IllegalArgumentException if {@code stage} is not a processing stage
     */
    public MidasState choiceFor(MidasState stage) {
        requireProcessingStage(stage);
        return choiceByStage.get(stage);
    }

    /**
     * The stage entered after {@code stage} validates successfully. Returns
     * {@link MidasState#COMPLETED} for the final processing stage.
     *
     * @throws IllegalArgumentException if {@code stage} is not a processing stage
     */
    public MidasState nextStage(MidasState stage) {
        requireProcessingStage(stage);
        return nextByStage.get(stage);
    }

    /**
     * Context-aware successor for dynamic routing. After {@link MidasState#ARCHITECTURE_DESIGN},
     * skips {@link MidasState#INTEGRATION_STRATEGY} when upstream artifacts explicitly declare
     * {@code has_external_integrations: false}.
     */
    public MidasState nextStage(MidasState stage, MidasContext ctx) {
        requireProcessingStage(stage);
        Objects.requireNonNull(ctx, "ctx must not be null");
        if (stage == MidasState.ARCHITECTURE_DESIGN && shouldSkipIntegrationStage(ctx)) {
            return MidasState.CODE_GENERATION;
        }
        return nextByStage.get(stage);
    }

    /**
     * {@code true} when {@code has_external_integrations} is explicitly {@code false} on the
     * validated architecture output or on the stored technical specification.
     */
    public boolean shouldSkipIntegrationStage(JsonNode validatedArchitecture, MidasContext ctx) {
        Objects.requireNonNull(ctx, "ctx must not be null");
        if (explicitNoExternalIntegrations(validatedArchitecture)) {
            return true;
        }
        return explicitNoExternalIntegrations(ctx.getTechnicalSpec());
    }

    /** {@code true} when stored context already carries an explicit no-integration flag. */
    public boolean shouldSkipIntegrationStage(MidasContext ctx) {
        Objects.requireNonNull(ctx, "ctx must not be null");
        if (explicitNoExternalIntegrations(ctx.getArchitectureDesign())) {
            return true;
        }
        return explicitNoExternalIntegrations(ctx.getTechnicalSpec());
    }

    /** {@code true} iff {@code stage} is the final processing stage (its successor is COMPLETED). */
    public boolean isFinalStage(MidasState stage) {
        return isProcessingStage(stage) && nextByStage.get(stage) == MidasState.COMPLETED;
    }

    /**
     * {@code true} iff {@code stage} is a blocking quality gate whose CHOICE routing must also
     * branch on the agent's verdict (a REJECT verdict terminates the pipeline in
     * {@link MidasState#ERROR} instead of advancing to its normal successor).
     */
    public boolean isQualityGate(MidasState stage) {
        return stage != null && QUALITY_GATES.contains(stage);
    }

    // ── Internals ──────────────────────────────────────────────────────────────

    private void requireProcessingStage(MidasState stage) {
        Objects.requireNonNull(stage, "stage must not be null");
        if (!choiceByStage.containsKey(stage)) {
            throw new IllegalArgumentException(
                    "State [" + stage + "] is not a routable processing stage of the pipeline topology.");
        }
    }

    private static boolean explicitNoExternalIntegrations(JsonNode node) {
        if (node == null || !node.isObject()) {
            return false;
        }
        JsonNode flag = node.get("has_external_integrations");
        return flag != null && flag.isBoolean() && !flag.asBoolean();
    }
}

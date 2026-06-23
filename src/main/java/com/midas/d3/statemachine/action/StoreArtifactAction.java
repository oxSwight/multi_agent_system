package com.midas.d3.statemachine.action;

import com.fasterxml.jackson.databind.JsonNode;
import com.midas.d3.context.AuditEntry;
import com.midas.d3.context.MidasContext;
import com.midas.d3.statemachine.AgentDispatcher;
import com.midas.d3.statemachine.MidasEvent;
import com.midas.d3.statemachine.MidasState;
import com.midas.d3.statemachine.PipelineContextKeys;
import com.midas.d3.statemachine.PipelineTopology;
import com.midas.d3.validation.ImplementationOutputUnwrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.statemachine.StateContext;
import org.springframework.statemachine.action.Action;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Action that fires on a successful validation transition (source → next stage).
 *
 * <p>Responsibilities:
 * <ol>
 *   <li>Reads the pre-validated {@link JsonNode} from
 *       {@link PipelineContextKeys#LAST_VALIDATED_NODE} (written by
 *       {@link com.midas.d3.statemachine.guard.ValidationPassedGuard}).</li>
 *   <li>Stores the artifact in the appropriate field of the immutable
 *       {@link MidasContext} via a {@code withXxx()} method.</li>
 *   <li>Resets the retry counter to 0.</li>
 *   <li>Appends an INFO audit entry.</li>
 *   <li>Cleans up transient ExtendedState keys.</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StoreArtifactAction implements Action<MidasState, MidasEvent> {

    private final AgentDispatcher agentDispatcher;
    private final PipelineCompletionAction pipelineCompletionAction;
    private final PipelineTopology topology;

    @Override
    public void execute(StateContext<MidasState, MidasEvent> context) {
        Map<Object, Object> vars = context.getExtendedState().getVariables();

        MidasContext current = (MidasContext) vars.get(PipelineContextKeys.MIDAS_CONTEXT);
        JsonNode validated   = (JsonNode) vars.get(PipelineContextKeys.LAST_VALIDATED_NODE);

        if (current == null) {
            log.error("[StoreArtifactAction] MidasContext missing — cannot store artifact.");
            return;
        }
        if (validated == null) {
            log.error("[StoreArtifactAction] LAST_VALIDATED_NODE missing — guard did not store result.");
            return;
        }

        // PENDING_STAGE is set by ValidateAndCaptureAction on the entry transition to the CHOICE state.
        // context.getSource() here would be the CHOICE pseudo-state, not the processing stage.
        MidasState sourceState = resolvePendingStage(context);
        MidasContext updated   = applyArtifact(current, sourceState, validated)
                .withValidationRetries(0)
                .appendAudit(AuditEntry.info(
                        sourceState != null ? sourceState.name() : "UNKNOWN",
                        "Artifact validated and stored successfully"));

        vars.put(PipelineContextKeys.MIDAS_CONTEXT, updated);
        vars.remove(PipelineContextKeys.LAST_VALIDATED_NODE);
        vars.remove(PipelineContextKeys.LAST_VALIDATION_ERROR);

        log.info("[StoreArtifactAction][{}] Artifact stored. Retries reset to 0.", sourceState);

        // CHOICE → next-stage transitions do not trigger stateEntry actions in SSM.
        // context.getTarget() is still the CHOICE pseudo-state here — derive the next stage
        // from the single source of truth (PipelineTopology) so this can never disagree with
        // the state machine's own routing.
        MidasState nextStage = topology.isProcessingStage(sourceState)
                ? topology.nextStage(sourceState, updated)
                : null;
        if (nextStage == MidasState.COMPLETED) {
            pipelineCompletionAction.execute(context);
        } else {
            // null-safe: AgentDispatcher.dispatchIfAutoMode ignores a null state.
            agentDispatcher.dispatchIfAutoMode(context.getStateMachine(), nextStage);
        }
    }

    /**
     * Maps the source state to the correct {@code MidasContext.withXxx()} call.
     * Non-processing states are no-ops — they should never reach this action.
     */
    private MidasContext applyArtifact(MidasContext ctx, MidasState state, JsonNode node) {
        if (state == null) return ctx;
        return switch (state) {
            case SYSTEM_ANALYSIS      -> ctx.withTechnicalSpec(node);
            case ARCHITECTURE_DESIGN  -> ctx.withArchitectureDesign(node);
            case INTEGRATION_STRATEGY -> ctx.withIntegrationStrategy(node);
            case CODE_GENERATION      -> storeCodeGeneration(ctx, node);
            case TEST_GENERATION      -> ctx.withGeneratedTests(node);
            case BUILD_VERIFICATION   -> ctx.withBuildReport(node);
            case SECOPS_AUDIT         -> ctx.withSecOpsArtifacts(node);
            case PRODUCT_REVIEW       -> ctx.withProductReviewReport(node);
            default -> {
                log.warn("[StoreArtifactAction] Unexpected source state [{}] — artifact not stored.", state);
                yield ctx;
            }
        };
    }

    private MidasContext storeCodeGeneration(MidasContext ctx, JsonNode node) {
        if (node.has("source_files") && node.has("feature_manifest")) {
            ImplementationOutputUnwrapper.UnwrappedEnvelope unwrapped =
                    ImplementationOutputUnwrapper.unwrap(node);
            return ctx.withGeneratedSourceCode(unwrapped.sourceFiles())
                    .withFeatureManifest(unwrapped.featureManifest());
        }
        return ctx.withGeneratedSourceCode(node);
    }

    private MidasState resolvePendingStage(StateContext<MidasState, MidasEvent> context) {
        Object raw = context.getExtendedState().getVariables().get(PipelineContextKeys.PENDING_STAGE);
        if (raw instanceof MidasState s) return s;
        log.error("[StoreArtifactAction] PENDING_STAGE not set — cannot determine artifact field.");
        return null;
    }
}

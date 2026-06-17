package com.midas.d3.statemachine.guard;

import com.fasterxml.jackson.databind.JsonNode;
import com.midas.d3.context.MidasContext;
import com.midas.d3.statemachine.MidasEvent;
import com.midas.d3.statemachine.MidasState;
import com.midas.d3.statemachine.PipelineContextKeys;
import com.midas.d3.statemachine.ValidatorRegistry;
import com.midas.d3.validation.GoalKeeperValidator;
import com.midas.d3.validation.ValidationHookException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.statemachine.StateContext;
import org.springframework.statemachine.guard.Guard;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * Guard that evaluates whether the LLM output in the current event message is
 * structurally valid for the active pipeline stage.
 *
 * <p><b>Contract:</b>
 * <ul>
 *   <li>Returns {@code true}  → validation passed; stores the parsed {@link JsonNode}
 *       under {@link PipelineContextKeys#LAST_VALIDATED_NODE} for the subsequent action.</li>
 *   <li>Returns {@code false} → validation failed; stores the error message under
 *       {@link PipelineContextKeys#LAST_VALIDATION_ERROR} for diagnostic logging.</li>
 * </ul>
 *
 * <p><b>Priority:</b> Evaluated FIRST among the three competing {@code SUBMIT_RESULT}
 * transitions for each stage. SSM external transitions take precedence over internal
 * transitions, and among externals they are evaluated in definition order.
 *
 * <p><b>Note on side effects:</b> Storing validated output in {@code ExtendedState}
 * is intentional — the guard is the only component that has both the raw LLM string
 * and access to the correct validator. The paired action ({@code StoreArtifactAction})
 * is responsible for promoting that result into the immutable {@link MidasContext}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ValidationPassedGuard implements Guard<MidasState, MidasEvent> {

    private final ValidatorRegistry validatorRegistry;

    @Override
    public boolean evaluate(StateContext<MidasState, MidasEvent> context) {
        MidasState sourceState = resolveSourceState(context);
        if (sourceState == null) {
            log.warn("[ValidationPassedGuard] Cannot determine source state — returning false.");
            return false;
        }

        String llmOutput = extractLlmOutput(context);
        if (llmOutput == null) {
            log.warn("[ValidationPassedGuard][{}] llmOutput header is missing — returning false.", sourceState);
            storeError(context, "llmOutput header is null or blank.");
            return false;
        }

        Optional<GoalKeeperValidator> validatorOpt = validatorRegistry.getValidator(sourceState);
        if (validatorOpt.isEmpty()) {
            log.warn("[ValidationPassedGuard][{}] No validator registered for state.", sourceState);
            storeError(context, "No validator registered for state: " + sourceState);
            return false;
        }

        try {
            JsonNode validated = validatorOpt.get().validate(llmOutput);
            storeValidated(context, validated);
            log.debug("[ValidationPassedGuard][{}] Validation PASSED.", sourceState);
            return true;

        } catch (ValidationHookException e) {
            log.warn("[ValidationPassedGuard][{}] Validation FAILED: {}", sourceState, e.getMessage());
            storeError(context, e.getMessage());
            return false;
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private MidasState resolveSourceState(StateContext<MidasState, MidasEvent> context) {
        try {
            // Prefer the transition's declared source (most precise)
            if (context.getSource() != null && context.getSource().getId() != null) {
                return context.getSource().getId();
            }
            // Fallback: current machine state (works for self-transitions in SSM 4.x)
            if (context.getStateMachine() != null && context.getStateMachine().getState() != null) {
                return context.getStateMachine().getState().getId();
            }
            return null;
        } catch (Exception e) {
            log.error("[ValidationPassedGuard] Failed to resolve source state.", e);
            return null;
        }
    }

    private String extractLlmOutput(StateContext<MidasState, MidasEvent> context) {
        Object raw = context.getMessageHeader(PipelineContextKeys.LLM_OUTPUT_HEADER);
        if (!(raw instanceof String str)) return null;
        return str.isBlank() ? null : str;
    }

    private void storeValidated(StateContext<MidasState, MidasEvent> context, JsonNode node) {
        Map<Object, Object> vars = context.getExtendedState().getVariables();
        vars.put(PipelineContextKeys.LAST_VALIDATED_NODE, node);
        vars.remove(PipelineContextKeys.LAST_VALIDATION_ERROR);
    }

    private void storeError(StateContext<MidasState, MidasEvent> context, String message) {
        context.getExtendedState().getVariables()
                .put(PipelineContextKeys.LAST_VALIDATION_ERROR, message);
    }
}

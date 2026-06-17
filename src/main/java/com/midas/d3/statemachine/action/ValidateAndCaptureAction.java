package com.midas.d3.statemachine.action;

import com.fasterxml.jackson.databind.JsonNode;
import com.midas.d3.statemachine.MidasEvent;
import com.midas.d3.statemachine.MidasState;
import com.midas.d3.statemachine.PipelineContextKeys;
import com.midas.d3.statemachine.ValidatorRegistry;
import com.midas.d3.validation.GoalKeeperValidator;
import com.midas.d3.validation.ValidationHookException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.statemachine.StateContext;
import org.springframework.statemachine.action.Action;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * Action that fires on the transition from a processing stage to its CHOICE pseudo-state
 * (e.g., SYSTEM_ANALYSIS → ANALYSIS_CHOICE on {@code SUBMIT_RESULT}).
 *
 * <p>Responsibilities:
 * <ol>
 *   <li>Captures the source processing stage into {@link PipelineContextKeys#PENDING_STAGE}
 *       so that downstream actions ({@link StoreArtifactAction}) know which artifact
 *       field to update.</li>
 *   <li>Validates the LLM output string from the message header against the
 *       appropriate {@link GoalKeeperValidator} for that stage.</li>
 *   <li>On success: stores the validated {@link JsonNode} in
 *       {@link PipelineContextKeys#LAST_VALIDATED_NODE}.</li>
 *   <li>On failure: stores the error message in
 *       {@link PipelineContextKeys#LAST_VALIDATION_ERROR}.</li>
 * </ol>
 *
 * <p>The CHOICE state's branch guards ({@link com.midas.d3.statemachine.guard.ValidationSucceededGuard},
 * {@link com.midas.d3.statemachine.guard.RetriesExhaustedGuard}) then read from
 * ExtendedState to decide the next state — without re-running the validation logic.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ValidateAndCaptureAction implements Action<MidasState, MidasEvent> {

    private final ValidatorRegistry validatorRegistry;

    @Override
    public void execute(StateContext<MidasState, MidasEvent> context) {
        Map<Object, Object> vars = context.getExtendedState().getVariables();

        // Capture the originating processing stage for StoreArtifactAction
        MidasState sourceStage = resolveSourceStage(context);
        vars.put(PipelineContextKeys.PENDING_STAGE, sourceStage);

        // Extract LLM output from message header
        String llmOutput = extractLlmOutput(context);
        if (llmOutput == null) {
            log.warn("[ValidateAndCaptureAction][{}] llmOutput header is null or blank.", sourceStage);
            vars.remove(PipelineContextKeys.LAST_VALIDATED_NODE);
            vars.put(PipelineContextKeys.LAST_VALIDATION_ERROR, "llmOutput header is null or blank.");
            return;
        }

        // Look up the validator for this stage
        Optional<GoalKeeperValidator> validatorOpt = validatorRegistry.getValidator(sourceStage);
        if (validatorOpt.isEmpty()) {
            log.warn("[ValidateAndCaptureAction][{}] No validator registered.", sourceStage);
            vars.remove(PipelineContextKeys.LAST_VALIDATED_NODE);
            vars.put(PipelineContextKeys.LAST_VALIDATION_ERROR,
                    "No validator registered for stage: " + sourceStage);
            return;
        }

        // Validate
        try {
            JsonNode validated = validatorOpt.get().validate(llmOutput);
            vars.put(PipelineContextKeys.LAST_VALIDATED_NODE, validated);
            vars.remove(PipelineContextKeys.LAST_VALIDATION_ERROR);
            log.debug("[ValidateAndCaptureAction][{}] Validation PASSED.", sourceStage);
        } catch (ValidationHookException e) {
            vars.remove(PipelineContextKeys.LAST_VALIDATED_NODE);
            vars.put(PipelineContextKeys.LAST_VALIDATION_ERROR, e.getMessage());
            log.warn("[ValidateAndCaptureAction][{}] Validation FAILED: {}", sourceStage, e.getMessage());
        }
    }

    private MidasState resolveSourceStage(StateContext<MidasState, MidasEvent> context) {
        try {
            if (context.getSource() != null && context.getSource().getId() != null) {
                return context.getSource().getId();
            }
        } catch (Exception ignored) {}
        return null;
    }

    private String extractLlmOutput(StateContext<MidasState, MidasEvent> context) {
        Object raw = context.getMessageHeader(PipelineContextKeys.LLM_OUTPUT_HEADER);
        if (!(raw instanceof String str)) return null;
        return str.isBlank() ? null : str;
    }
}

package com.midas.d3.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for {@code POST /api/v1/pipelines/{runId}/submit}.
 *
 * <p>{@code llmOutput} is the raw string produced by the LLM — it may
 * contain markdown fences or escaped characters.  {@link com.midas.d3.sanitizer.JsonSanitizer}
 * is applied downstream inside the state machine actions.
 */
public record SubmitResultRequest(
        @NotBlank(message = "llmOutput must not be blank")
        String llmOutput
) {}

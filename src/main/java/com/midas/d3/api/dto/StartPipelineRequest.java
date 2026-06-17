package com.midas.d3.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for {@code POST /api/v1/pipelines}.
 */
public record StartPipelineRequest(
        @NotBlank(message = "rawUserIdea must not be blank")
        String rawUserIdea,

        /** When true, agents are dispatched automatically (same as Telegram bot mode). */
        Boolean autoMode
) {
    public boolean isAutoMode() {
        return Boolean.TRUE.equals(autoMode);
    }
}

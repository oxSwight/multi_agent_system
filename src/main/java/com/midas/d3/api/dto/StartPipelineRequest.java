package com.midas.d3.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/v1/pipelines}.
 *
 * <p><b>Security:</b> a REST caller deliberately CANNOT supply a Telegram chat/message binding here.
 * Allowing it let any token holder deliver another user's generated artifact + progress to an arbitrary
 * chat. Telegram-initiated runs are started by the bot itself (which supplies the real chat id from the
 * incoming update), never through this endpoint.
 */
public record StartPipelineRequest(
        @NotBlank(message = "rawUserIdea must not be blank")
        @Size(max = 20_000, message = "rawUserIdea must be at most 20000 characters")
        String rawUserIdea,

        /** When true, agents are dispatched automatically (same as Telegram bot mode). */
        Boolean autoMode
) {
    public boolean isAutoMode() {
        return Boolean.TRUE.equals(autoMode);
    }
}

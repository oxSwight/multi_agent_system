package com.midas.d3.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for {@code POST /api/v1/pipelines}.
 */
public record StartPipelineRequest(
        @NotBlank(message = "rawUserIdea must not be blank")
        String rawUserIdea,

        /** When true, agents are dispatched automatically (same as Telegram bot mode). */
        Boolean autoMode,

        /** Optional Telegram chat ID — with {@code telegramMessageId}, enables progress updates in chat. */
        Long telegramChatId,

        /** Optional Telegram message ID to edit with pipeline progress. */
        Integer telegramMessageId
) {
    public boolean isAutoMode() {
        return Boolean.TRUE.equals(autoMode);
    }

    public boolean hasTelegramBinding() {
        return telegramChatId != null && telegramMessageId != null;
    }
}

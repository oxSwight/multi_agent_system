package com.midas.d3.llm;

import java.util.Objects;

public record LlmCallResult(
        String text,
        String modelUsed,
        int promptTokens,
        int completionTokens,
        String finishReason
) {
    public static final String FINISH_REASON_MAX_TOKENS = "MAX_TOKENS";

    public LlmCallResult {
        Objects.requireNonNull(text, "text must not be null");
        Objects.requireNonNull(modelUsed, "modelUsed must not be null");
        if (finishReason == null) {
            finishReason = "";
        }
    }

    public static LlmCallResult ofText(String text) {
        return new LlmCallResult(text, "", 0, 0, "");
    }

    public static LlmCallResult of(String text, String modelUsed, int promptTokens, int completionTokens) {
        return new LlmCallResult(text, modelUsed, promptTokens, completionTokens, "");
    }

    public static LlmCallResult of(String text, String modelUsed, int promptTokens,
                                   int completionTokens, String finishReason) {
        return new LlmCallResult(text, modelUsed, promptTokens, completionTokens, finishReason);
    }

    public boolean isTruncated() {
        return FINISH_REASON_MAX_TOKENS.equals(finishReason);
    }
}

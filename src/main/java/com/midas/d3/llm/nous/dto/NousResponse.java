package com.midas.d3.llm.nous.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Optional;

/**
 * OpenAI-compatible chat completions response payload.
 * Handles both OpenRouter and LM Studio response shapes.
 */
@Getter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class NousResponse {

    private List<Choice> choices;

    /**
     * Extracts the assistant's reply text from the first choice.
     *
     * @return non-empty Optional if content is present and non-blank; empty otherwise
     */
    public Optional<String> extractFinishReason() {
        if (choices == null || choices.isEmpty()) {
            return Optional.empty();
        }
        String reason = choices.get(0).getFinishReason();
        return (reason == null || reason.isBlank()) ? Optional.empty() : Optional.of(reason);
    }

    public Optional<String> extractText() {
        if (choices == null || choices.isEmpty()) {
            return Optional.empty();
        }
        Choice first = choices.get(0);
        if (first.getMessage() == null) {
            return Optional.empty();
        }
        String content = first.getMessage().getContent();
        if (content != null && !content.isBlank()) {
            return Optional.of(content.strip());
        }
        // Ollama DeepSeek-R1 isolates chain-of-thought in reasoning; JSON may appear there when content is empty.
        String reasoning = first.getMessage().getReasoning();
        if (reasoning != null && !reasoning.isBlank()) {
            return Optional.of(reasoning.strip());
        }
        return Optional.empty();
    }

    /** {@code true} when {@link #extractText()} would fall back to the Ollama {@code reasoning} field. */
    public boolean usedReasoningFallback() {
        if (choices == null || choices.isEmpty()) {
            return false;
        }
        Choice first = choices.get(0);
        if (first.getMessage() == null) {
            return false;
        }
        String content = first.getMessage().getContent();
        if (content != null && !content.isBlank()) {
            return false;
        }
        String reasoning = first.getMessage().getReasoning();
        return reasoning != null && !reasoning.isBlank();
    }

    // ── Inner DTOs ───────────────────────────────────────────────────────────

    @Getter
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Choice {
        private Message       message;
        private Integer       index;
        @com.fasterxml.jackson.annotation.JsonProperty("finish_reason")
        private String        finishReason;
    }

    @Getter
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Message {
        private String role;
        private String content;
        /** Ollama DeepSeek-R1 chain-of-thought; ignored for artifact extraction. */
        private String reasoning;
    }
}

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
    public Optional<String> extractText() {
        if (choices == null || choices.isEmpty()) {
            return Optional.empty();
        }
        Choice first = choices.get(0);
        if (first.getMessage() == null) {
            return Optional.empty();
        }
        String content = first.getMessage().getContent();
        return (content == null || content.isBlank())
                ? Optional.empty()
                : Optional.of(content.strip());
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
    }
}

package com.midas.d3.llm.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;
import java.util.Optional;

/**
 * Partial Gemini API response deserialization.
 * Only the fields needed to extract the generated text are mapped.
 */
@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public final class GeminiResponse {

    private List<Candidate> candidates;
    private PromptFeedback promptFeedback;

    /**
     * Extracts the generated text from the first candidate's first part.
     *
     * @return the raw text, or {@link Optional#empty()} if the response structure
     *         is missing or malformed (e.g., blocked by safety filters)
     */
    public Optional<String> extractText() {
        if (candidates == null || candidates.isEmpty()) return Optional.empty();
        Candidate first = candidates.get(0);
        if (first.getContent() == null) return Optional.empty();
        List<Part> parts = first.getContent().getParts();
        if (parts == null || parts.isEmpty()) return Optional.empty();
        String text = parts.get(0).getText();
        return (text == null || text.isBlank()) ? Optional.empty() : Optional.of(text);
    }

    // ── Nested DTOs ───────────────────────────────────────────────────────────

    @Getter @Setter @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Candidate {
        private Content content;
        private String finishReason;
        private int index;
    }

    @Getter @Setter @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Content {
        private List<Part> parts;
        private String role;
    }

    @Getter @Setter @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Part {
        private String text;
    }

    @Getter @Setter @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class PromptFeedback {
        @JsonProperty("blockReason")
        private String blockReason;
    }
}

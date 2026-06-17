package com.midas.d3.llm.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * Gemini API request payload.
 * Matches the structure expected by:
 * {@code POST /v1beta/models/{model}:generateContent?key={apiKey}}
 */
@Getter
@Builder
public final class GeminiRequest {

    @JsonProperty("system_instruction")
    private final SystemInstruction systemInstruction;

    private final List<Content> contents;

    @JsonProperty("generationConfig")
    private final GenerationConfig generationConfig;

    // ── Nested DTOs ───────────────────────────────────────────────────────────

    @Getter
    @Builder
    public static final class SystemInstruction {
        private final List<Part> parts;

        public static SystemInstruction of(String text) {
            return SystemInstruction.builder().parts(List.of(Part.of(text))).build();
        }
    }

    @Getter
    @Builder
    public static final class Content {
        private final String role;
        private final List<Part> parts;

        public static Content user(String text) {
            return Content.builder().role("user").parts(List.of(Part.of(text))).build();
        }
    }

    @Getter
    @Builder
    public static final class Part {
        private final String text;

        public static Part of(String text) {
            return Part.builder().text(text).build();
        }
    }

    @Getter
    @Builder
    public static final class GenerationConfig {

        private final double temperature;

        @JsonProperty("responseMimeType")
        private final String responseMimeType;

        @JsonProperty("maxOutputTokens")
        private final int maxOutputTokens;

        public static GenerationConfig jsonMode() {
            return GenerationConfig.builder()
                    .temperature(0.1)
                    .responseMimeType("application/json")
                    .maxOutputTokens(65536)
                    .build();
        }
    }

    // ── Factory ───────────────────────────────────────────────────────────────

    public static GeminiRequest of(String systemPrompt, String userMessage) {
        return GeminiRequest.builder()
                .systemInstruction(SystemInstruction.of(systemPrompt))
                .contents(List.of(Content.user(userMessage)))
                .generationConfig(GenerationConfig.jsonMode())
                .build();
    }
}

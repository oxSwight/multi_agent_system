package com.midas.d3.llm.nous.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * OpenAI-compatible chat completions request payload.
 * Compatible with OpenRouter (cloud) and LM Studio (local).
 */
@Getter
@Builder
public class NousRequest {

    private final String        model;
    private final List<Message> messages;
    private final double        temperature;

    @JsonProperty("max_tokens")
    private final int maxTokens;

    // ── Factory ─────────────────────────────────────────────────────────────

    public static NousRequest of(String model, String systemPrompt, String userContent) {
        return NousRequest.builder()
                .model(model)
                .messages(List.of(Message.system(systemPrompt), Message.user(userContent)))
                .temperature(0.1)
                .maxTokens(8192)
                .build();
    }

    // ── Inner DTO ────────────────────────────────────────────────────────────

    @Getter
    @Builder
    public static class Message {
        private final String role;
        private final String content;

        public static Message system(String content) {
            return Message.builder().role("system").content(content).build();
        }

        public static Message user(String content) {
            return Message.builder().role("user").content(content).build();
        }
    }
}

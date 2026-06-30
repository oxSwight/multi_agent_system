package com.midas.d3.llm.nous.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;

import java.util.ArrayList;
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
        return of(model, systemPrompt, userContent, "");
    }

    /**
     * Builds the chat payload with the prompt-cache split honored at message boundaries:
     * {@code [system, user(cacheableUserPrefix)]} is the stable, cacheable prefix; a non-blank
     * {@code volatileSuffix} (per-attempt correction feedback) is carried as its own trailing user
     * message. This keeps the first two messages byte-identical across retries, so a caching backend
     * (OpenRouter/OpenAI-compatible prefix cache, or a local Ollama KV cache) reuses them instead of
     * re-billing the whole context on every correction round.
     */
    public static NousRequest of(String model, String systemPrompt,
                                 String cacheableUserPrefix, String volatileSuffix) {
        return of(model, systemPrompt, cacheableUserPrefix, volatileSuffix, 0);
    }

    /**
     * Same as {@link #of(String, String, String, String)} but with an explicit per-call completion
     * budget. A non-positive {@code maxTokens} falls back to the per-model default — so callers that
     * do not compute a stage budget keep the previous behavior.
     */
    public static NousRequest of(String model, String systemPrompt,
                                 String cacheableUserPrefix, String volatileSuffix, int maxTokens) {
        List<Message> messages = new ArrayList<>(3);
        messages.add(Message.system(systemPrompt));
        messages.add(Message.user(cacheableUserPrefix));
        if (volatileSuffix != null && !volatileSuffix.isBlank()) {
            messages.add(Message.user(volatileSuffix));
        }
        return NousRequest.builder()
                .model(model)
                .messages(List.copyOf(messages))
                .temperature(0.1)
                .maxTokens(maxTokens > 0 ? maxTokens : resolveMaxTokens(model))
                .build();
    }

    /** Reasoning models (DeepSeek-R1) need a larger budget: Ollama fills {@code reasoning} first. */
    private static int resolveMaxTokens(String model) {
        if (model != null) {
            String lower = model.toLowerCase();
            if (lower.contains("deepseek-r1") || lower.contains("r1:")) {
                return 16_384;
            }
        }
        return 8_192;
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

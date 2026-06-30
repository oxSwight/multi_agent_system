package com.midas.d3.llm;

import com.midas.d3.statemachine.MidasState;
import lombok.Builder;
import lombok.Getter;

import java.util.Objects;

/**
 * Immutable request descriptor for a single LLM agent invocation.
 *
 * <h2>Prompt-cache contract</h2>
 * The payload is split into a <b>stable cacheable prefix</b> and a <b>volatile suffix</b>:
 * <ul>
 *   <li>{@code systemPrompt} + {@code cacheableUserPrefix} are byte-identical across every retry
 *       attempt within one agent execution (the system prompt is static per agent; the base user
 *       message — user idea + upstream artifacts — does not change between attempts).</li>
 *   <li>{@code volatileSuffix} carries only the per-attempt correction feedback.</li>
 * </ul>
 * Keeping the correction in its <em>own</em> segment (rather than concatenated into the user
 * message) lets a caching-capable backend place a cache breakpoint at a clean boundary: token-prefix
 * cachers (OpenRouter/OpenAI-compatible, Gemini implicit) and block-level cachers (Anthropic
 * {@code cache_control}) both reuse the prefix, and a local llama.cpp/Ollama KV cache stays warm.
 * {@link #getUserMessage()} still returns the fully-assembled message for transports that don't
 * model the split.
 */
@Getter
@Builder
public final class LlmCallRequest {

    /** Which pipeline stage is invoking the LLM. Used for logging and tracing. */
    private final MidasState stage;

    /** Human-readable agent name for logging. */
    private final String agentName;

    /** Full system prompt text for this agent. Part of the stable cacheable prefix. */
    private final String systemPrompt;

    /** Fully-assembled user message ({@code cacheableUserPrefix} + any {@code volatileSuffix}). */
    private final String userMessage;

    /**
     * The stable, cacheable portion of the user message (user idea + upstream artifacts).
     * Invariant across retry attempts for a given agent execution.
     */
    private final String cacheableUserPrefix;

    /**
     * The volatile portion appended on retries (validation-correction feedback). Empty on the
     * first attempt. Never part of the cache key.
     */
    private final String volatileSuffix;

    /** Pipeline run ID for distributed tracing. */
    private final String pipelineRunId;

    private final String modelOverride;

    public static LlmCallRequest of(MidasState stage,
                                    String agentName,
                                    String systemPrompt,
                                    String userMessage,
                                    String pipelineRunId) {
        return of(stage, agentName, systemPrompt, userMessage, pipelineRunId, null);
    }

    public static LlmCallRequest of(MidasState stage,
                                    String agentName,
                                    String systemPrompt,
                                    String userMessage,
                                    String pipelineRunId,
                                    String modelOverride) {
        // The whole user message is the cacheable prefix; no volatile suffix yet.
        return build(stage, agentName, systemPrompt, userMessage, "", pipelineRunId, modelOverride);
    }

    /**
     * Returns a copy of this request carrying the given per-attempt correction feedback as the
     * volatile suffix. The cacheable prefix ({@code systemPrompt} + {@code cacheableUserPrefix}) is
     * preserved verbatim, so a cache-capable backend reuses it across attempts. A blank correction
     * yields a request with no volatile suffix.
     */
    public LlmCallRequest withCorrectionFeedback(String correctionFeedback) {
        return build(stage, agentName, systemPrompt, cacheableUserPrefix,
                correctionFeedback == null ? "" : correctionFeedback, pipelineRunId, modelOverride);
    }

    /**
     * Returns a copy of this request routed to a different model, preserving the cacheable prefix and
     * any volatile suffix verbatim. Used by deliberate model escalation (F5) to re-issue the final
     * retry on a stronger model without disturbing the prompt-cache split.
     */
    public LlmCallRequest withModelOverride(String newModelOverride) {
        return build(stage, agentName, systemPrompt, cacheableUserPrefix,
                volatileSuffix, pipelineRunId, newModelOverride);
    }

    /** True when this request carries per-attempt correction feedback after the cacheable prefix. */
    public boolean hasVolatileSuffix() {
        return volatileSuffix != null && !volatileSuffix.isBlank();
    }

    private static LlmCallRequest build(MidasState stage,
                                        String agentName,
                                        String systemPrompt,
                                        String cacheableUserPrefix,
                                        String volatileSuffix,
                                        String pipelineRunId,
                                        String modelOverride) {
        Objects.requireNonNull(stage,               "stage must not be null");
        Objects.requireNonNull(agentName,           "agentName must not be null");
        Objects.requireNonNull(systemPrompt,        "systemPrompt must not be null");
        Objects.requireNonNull(cacheableUserPrefix, "userMessage must not be null");
        Objects.requireNonNull(pipelineRunId,       "pipelineRunId must not be null");

        if (systemPrompt.isBlank())        throw new IllegalArgumentException("systemPrompt must not be blank");
        if (cacheableUserPrefix.isBlank()) throw new IllegalArgumentException("userMessage must not be blank");

        String suffix = volatileSuffix == null ? "" : volatileSuffix;
        String fullUserMessage = suffix.isBlank()
                ? cacheableUserPrefix
                : cacheableUserPrefix + "\n\n" + suffix;

        return LlmCallRequest.builder()
                .stage(stage).agentName(agentName)
                .systemPrompt(systemPrompt)
                .userMessage(fullUserMessage)
                .cacheableUserPrefix(cacheableUserPrefix)
                .volatileSuffix(suffix)
                .pipelineRunId(pipelineRunId)
                .modelOverride(modelOverride)
                .build();
    }
}

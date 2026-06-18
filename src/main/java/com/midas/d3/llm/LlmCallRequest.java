package com.midas.d3.llm;

import com.midas.d3.statemachine.MidasState;
import lombok.Builder;
import lombok.Getter;

import java.util.Objects;

/**
 * Immutable request descriptor for a single LLM agent invocation.
 */
@Getter
@Builder
public final class LlmCallRequest {

    /** Which pipeline stage is invoking the LLM. Used for logging and tracing. */
    private final MidasState stage;

    /** Human-readable agent name for logging. */
    private final String agentName;

    /** Full system prompt text for this agent. */
    private final String systemPrompt;

    /** Compact JSON user message built from {@link com.midas.d3.context.AgentContextView}. */
    private final String userMessage;

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
        Objects.requireNonNull(stage,        "stage must not be null");
        Objects.requireNonNull(agentName,    "agentName must not be null");
        Objects.requireNonNull(systemPrompt, "systemPrompt must not be null");
        Objects.requireNonNull(userMessage,  "userMessage must not be null");
        Objects.requireNonNull(pipelineRunId,"pipelineRunId must not be null");

        if (systemPrompt.isBlank()) throw new IllegalArgumentException("systemPrompt must not be blank");
        if (userMessage.isBlank())  throw new IllegalArgumentException("userMessage must not be blank");

        return LlmCallRequest.builder()
                .stage(stage).agentName(agentName)
                .systemPrompt(systemPrompt).userMessage(userMessage)
                .pipelineRunId(pipelineRunId)
                .modelOverride(modelOverride)
                .build();
    }
}

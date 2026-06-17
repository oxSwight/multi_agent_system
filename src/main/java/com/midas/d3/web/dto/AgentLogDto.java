package com.midas.d3.web.dto;

import com.midas.d3.persistence.entity.MidasAgentLogEntity;

/**
 * Represents a single agent invocation log entry within a pipeline run.
 *
 * @param agentType        class name of the agent (e.g. {@code "ImplementationEngineerAgent"})
 * @param rawOutput        raw LLM output or error message produced by the agent
 * @param executionTimeMs  wall-clock duration of the agent invocation in milliseconds
 * @param promptTokens     prompt tokens consumed by this single invocation
 * @param completionTokens completion tokens produced by this single invocation
 * @param isError          {@code true} if the agent threw an exception during this invocation
 */
public record AgentLogDto(
        String agentType,
        String rawOutput,
        long executionTimeMs,
        int promptTokens,
        int completionTokens,
        boolean isError
) {
    public static AgentLogDto from(MidasAgentLogEntity entity) {
        return new AgentLogDto(
                entity.getAgentType(),
                entity.getRawOutput(),
                entity.getExecutionTimeMs(),
                entity.getPromptTokens(),
                entity.getCompletionTokens(),
                entity.isError()
        );
    }
}

package com.midas.d3.web.dto;

import com.midas.d3.persistence.entity.MidasAgentLogEntity;

public record AgentLogDto(
        String agentType,
        String rawOutput,
        long executionTimeMs,
        int promptTokens,
        int completionTokens,
        String modelId,
        boolean isError
) {
    public static AgentLogDto from(MidasAgentLogEntity entity) {
        return new AgentLogDto(
                entity.getAgentType(),
                entity.getRawOutput(),
                entity.getExecutionTimeMs(),
                entity.getPromptTokens(),
                entity.getCompletionTokens(),
                entity.getModelId(),
                entity.isError()
        );
    }
}

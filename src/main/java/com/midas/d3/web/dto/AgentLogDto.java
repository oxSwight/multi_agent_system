package com.midas.d3.web.dto;

import com.midas.d3.persistence.entity.MidasAgentLogEntity;
import com.midas.d3.web.FinOpsCostEstimator;

public record AgentLogDto(
        String agentType,
        String rawOutput,
        long executionTimeMs,
        int promptTokens,
        int completionTokens,
        String modelId,
        String finishReason,
        Double estimatedCostUsd,
        boolean isError
) {
    public static AgentLogDto from(MidasAgentLogEntity entity, FinOpsCostEstimator costEstimator) {
        return new AgentLogDto(
                entity.getAgentType(),
                entity.getRawOutput(),
                entity.getExecutionTimeMs(),
                entity.getPromptTokens(),
                entity.getCompletionTokens(),
                entity.getModelId(),
                entity.getFinishReason(),
                costEstimator.estimateCostUsd(entity.getPromptTokens(), entity.getCompletionTokens()),
                entity.isError()
        );
    }
}

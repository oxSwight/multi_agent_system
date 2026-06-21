package com.midas.d3.web.dto;

import com.midas.d3.persistence.entity.MidasRunEntity;
import com.midas.d3.web.FinOpsCostEstimator;

import java.time.Instant;
import java.util.List;

/**
 * Full detail view of a single pipeline run, combining the summary fields
 * from {@link RunItemDto} with the ordered list of per-agent invocation logs.
 *
 * <p>Returned by {@code GET /api/v1/dashboard/runs/{runId}}.
 *
 * @param id                unique pipeline run identifier
 * @param status            current pipeline state
 * @param rawUserIdea       the original user prompt
 * @param artifactPath      path to the generated artifacts ZIP; {@code null} until COMPLETED
 * @param promptTokens      aggregated prompt token count for this run
 * @param completionTokens  aggregated completion token count for this run
 * @param createdAt         UTC timestamp of run creation
 * @param agentLogs         ordered (ASC by creation time) list of agent invocation logs
 * @param estimatedCostUsd  approximate run cost in USD (null when no tokens recorded)
 */
public record RunDetailsDto(
        String id,
        String status,
        String rawUserIdea,
        String artifactPath,
        int promptTokens,
        int completionTokens,
        Double estimatedCostUsd,
        Instant createdAt,
        List<AgentLogDto> agentLogs
) {
    public static RunDetailsDto from(MidasRunEntity entity, List<AgentLogDto> agentLogs,
                                     FinOpsCostEstimator costEstimator) {
        return new RunDetailsDto(
                entity.getId(),
                entity.getStatus(),
                entity.getRawUserIdea(),
                entity.getArtifactPath(),
                entity.getTotalPromptTokens(),
                entity.getTotalCompletionTokens(),
                costEstimator.estimateCostUsd(entity.getTotalPromptTokens(), entity.getTotalCompletionTokens()),
                entity.getCreatedAt(),
                agentLogs
        );
    }
}

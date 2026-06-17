package com.midas.d3.web.dto;

import com.midas.d3.persistence.entity.MidasRunEntity;

import java.time.Instant;

/**
 * Lightweight projection of a {@code midas_run} row for the runs list view.
 *
 * @param id                unique pipeline run identifier (UUID string)
 * @param status            current pipeline state (e.g. {@code "COMPLETED"}, {@code "ERROR"})
 * @param rawUserIdea       the original user prompt that started this run
 * @param artifactPath      path to the generated artifacts ZIP; {@code null} until COMPLETED
 * @param promptTokens      aggregated prompt token count for this run
 * @param completionTokens  aggregated completion token count for this run
 * @param createdAt         UTC timestamp of run creation
 */
public record RunItemDto(
        String id,
        String status,
        String rawUserIdea,
        String artifactPath,
        int promptTokens,
        int completionTokens,
        Instant createdAt
) {
    public static RunItemDto from(MidasRunEntity entity) {
        return new RunItemDto(
                entity.getId(),
                entity.getStatus(),
                entity.getRawUserIdea(),
                entity.getArtifactPath(),
                entity.getTotalPromptTokens(),
                entity.getTotalCompletionTokens(),
                entity.getCreatedAt()
        );
    }
}

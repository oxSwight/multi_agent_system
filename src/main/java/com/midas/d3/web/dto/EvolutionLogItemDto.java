package com.midas.d3.web.dto;

import com.midas.d3.persistence.entity.MidasEvolutionLogEntity;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable projection of a single {@link MidasEvolutionLogEntity} entry.
 * Returned by {@code GET /api/v1/dashboard/evolution-history}.
 *
 * @param id                unique record identifier
 * @param runId             ID of the pipeline run that was analyzed
 * @param refactoringReport full Markdown report produced by the EvolutionAgent
 * @param createdAt         UTC timestamp when the analysis was completed
 */
public record EvolutionLogItemDto(
        UUID    id,
        String  runId,
        String  refactoringReport,
        Instant createdAt
) {
    /** Factory method — converts entity to DTO without any mapping library. */
    public static EvolutionLogItemDto from(MidasEvolutionLogEntity entity) {
        return new EvolutionLogItemDto(
                entity.getId(),
                entity.getRunId(),
                entity.getRefactoringReport(),
                entity.getCreatedAt()
        );
    }
}

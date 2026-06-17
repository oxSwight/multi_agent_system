package com.midas.d3.persistence.repository;

import com.midas.d3.persistence.entity.MidasAgentLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link MidasAgentLogEntity}.
 *
 * <p>Provides CRUD plus derived/JPQL queries for common analytical access
 * patterns: per-run logs, error filtering, and aggregate counts.
 */
@Repository
public interface MidasAgentLogRepository extends JpaRepository<MidasAgentLogEntity, UUID> {

    /**
     * Returns all log entries for a given pipeline run, ordered by creation time.
     * Used for display in the pipeline context response and audit trail.
     *
     * @param runId the pipeline run ID
     */
    @Query("SELECT l FROM MidasAgentLogEntity l WHERE l.run.id = :runId ORDER BY l.createdAt ASC")
    List<MidasAgentLogEntity> findByRunId(@Param("runId") String runId);

    /**
     * Returns log entries filtered by run and error flag.
     * Used to surface failed agent invocations for review.
     *
     * @param runId   the pipeline run ID
     * @param isError {@code true} to return only error entries
     */
    @Query("SELECT l FROM MidasAgentLogEntity l WHERE l.run.id = :runId AND l.isError = :isError ORDER BY l.createdAt ASC")
    List<MidasAgentLogEntity> findByRunIdAndIsError(@Param("runId") String runId,
                                                    @Param("isError") boolean isError);

    /**
     * Counts log entries for a given pipeline run.
     * Used in tests and analytics to verify agent invocation count.
     *
     * @param runId the pipeline run ID
     */
    @Query("SELECT COUNT(l) FROM MidasAgentLogEntity l WHERE l.run.id = :runId")
    long countByRunId(@Param("runId") String runId);

    /**
     * Returns aggregate execution time per agent type across all runs.
     * Foundation for the performance analytics dashboard (Stage 10).
     *
     * @return list of {@code [agentType, avgMs, maxMs, totalInvocations]}
     */
    @Query("""
            SELECT l.agentType,
                   AVG(l.executionTimeMs),
                   MAX(l.executionTimeMs),
                   COUNT(l)
            FROM MidasAgentLogEntity l
            WHERE l.isError = false
            GROUP BY l.agentType
            ORDER BY AVG(l.executionTimeMs) DESC
            """)
    List<Object[]> findPerformanceStatsByAgentType();

    /**
     * Returns the mean execution time across all successful agent invocations.
     * Used for the {@code avgExecutionTimeMs} field in the overview metrics.
     *
     * @return mean execution time in milliseconds, or {@code null} when no logs exist
     */
    @Query("SELECT AVG(l.executionTimeMs) FROM MidasAgentLogEntity l WHERE l.isError = false")
    Double avgExecutionTimeMs();

    /**
     * Ranks agent types by their total token consumption (prompt + completion).
     * The first row is the most expensive agent overall.
     *
     * @return list of {@code [agentType, totalTokens]} ordered by totalTokens DESC
     */
    @Query("""
            SELECT l.agentType, SUM(l.promptTokens + l.completionTokens)
            FROM MidasAgentLogEntity l
            GROUP BY l.agentType
            ORDER BY SUM(l.promptTokens + l.completionTokens) DESC
            """)
    List<Object[]> findAgentsByTotalTokenConsumption();
}

package com.midas.d3.persistence.repository;

import com.midas.d3.persistence.entity.MidasRunEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * Spring Data JPA repository for {@link MidasRunEntity}.
 *
 * <p>Provides CRUD operations inherited from {@link JpaRepository} plus
 * custom bulk-update queries for the most frequent write operations (status,
 * idea, artifact path). Bulk updates pass the {@link Instant} timestamp as a
 * parameter because JPA bulk UPDATE queries do NOT invoke {@code @PreUpdate}
 * lifecycle callbacks — so the {@code updatedAt} column must be managed
 * explicitly in these cases.
 */
@Repository
public interface MidasRunRepository extends JpaRepository<MidasRunEntity, String> {

    /**
     * Bulk-updates only the {@code status} and {@code updated_at} columns.
     * Avoids a full entity load + dirty-check cycle for high-frequency status changes.
     *
     * @param id        the pipeline run ID
     * @param status    the new status value
     * @param updatedAt the timestamp to write into {@code updated_at}
     */
    @Modifying
    @Query("UPDATE MidasRunEntity r SET r.status = :status, r.updatedAt = :updatedAt WHERE r.id = :id")
    void updateStatus(@Param("id") String id,
                      @Param("status") String status,
                      @Param("updatedAt") Instant updatedAt);

    /**
     * Bulk-updates {@code raw_user_idea} and {@code updated_at}.
     * Called when the user provides a clarification via Human-in-the-Loop.
     *
     * @param id          the pipeline run ID
     * @param rawUserIdea the enriched idea text
     * @param updatedAt   the timestamp to write into {@code updated_at}
     */
    @Modifying
    @Query("UPDATE MidasRunEntity r SET r.rawUserIdea = :rawUserIdea, r.updatedAt = :updatedAt WHERE r.id = :id")
    void updateRawUserIdea(@Param("id") String id,
                           @Param("rawUserIdea") String rawUserIdea,
                           @Param("updatedAt") Instant updatedAt);

    /**
     * Bulk-updates {@code artifact_path}, {@code status}, and {@code updated_at}.
     * Called by {@link com.midas.d3.statemachine.action.PipelineCompletionAction}
     * after the artifacts ZIP has been created.
     *
     * @param id           the pipeline run ID
     * @param artifactPath absolute path to the generated ZIP file
     * @param status       final status (typically {@code "COMPLETED"})
     * @param updatedAt    the timestamp to write into {@code updated_at}
     */
    @Modifying
    @Query("UPDATE MidasRunEntity r SET r.artifactPath = :artifactPath, r.status = :status, r.updatedAt = :updatedAt WHERE r.id = :id")
    void updateArtifactPathAndStatus(@Param("id") String id,
                                     @Param("artifactPath") String artifactPath,
                                     @Param("status") String status,
                                     @Param("updatedAt") Instant updatedAt);

    /** Returns all runs for a given Telegram chat, ordered newest-first. */
    List<MidasRunEntity> findByChatIdOrderByCreatedAtDesc(Long chatId);

    /** Returns all runs with the given status, ordered newest-first. */
    List<MidasRunEntity> findByStatusOrderByCreatedAtDesc(String status);

    /** Returns every run ordered newest-first — used by the dashboard runs list and token-usage timeline. */
    List<MidasRunEntity> findAllByOrderByCreatedAtDesc();

    @Modifying
    @Query("UPDATE MidasRunEntity r SET r.totalPromptTokens = r.totalPromptTokens + :promptTokens, "
            + "r.totalCompletionTokens = r.totalCompletionTokens + :completionTokens, "
            + "r.updatedAt = :updatedAt WHERE r.id = :id")
    void incrementTokenTotals(@Param("id") String id,
                              @Param("promptTokens") int promptTokens,
                              @Param("completionTokens") int completionTokens,
                              @Param("updatedAt") Instant updatedAt);

    /**
     * Returns a two-element {@code Object[]} containing the SUM of prompt tokens and
     * the SUM of completion tokens across all runs.  Both elements are {@code null}
     * when the table is empty.
     *
     * @return {@code [sumPromptTokens, sumCompletionTokens]}
     */
    @Query("SELECT SUM(r.totalPromptTokens), SUM(r.totalCompletionTokens) FROM MidasRunEntity r")
    Object[] sumTotalTokens();

    /**
     * Bulk-updates the {@code needs_refactoring} flag and {@code updated_at}.
     * Used by {@link com.midas.d3.persistence.PersistenceService} to queue a run
     * for Evolution Agent analysis ({@code flag=true}) and to clear it after
     * analysis completes ({@code flag=false}).
     *
     * @param id        the pipeline run ID
     * @param flag      new value of {@code needs_refactoring}
     * @param updatedAt timestamp to write into {@code updated_at}
     */
    @Modifying
    @Query("UPDATE MidasRunEntity r SET r.needsRefactoring = :flag, r.updatedAt = :updatedAt WHERE r.id = :id")
    void updateNeedsRefactoring(@Param("id") String id,
                                @Param("flag") boolean flag,
                                @Param("updatedAt") Instant updatedAt);

    /**
     * Returns a {@link Page} of runs with the given status and
     * {@code needs_refactoring = true}, ordered oldest-first so the Evolution Agent
     * processes runs in the order they were completed.
     *
     * <p>Callers pass {@code PageRequest.of(0, 1)} to claim exactly one candidate
     * per scheduling cycle, preventing multiple simultaneous analyses of the same run.
     *
     * @param status   the status to filter on (typically {@code "COMPLETED"})
     * @param pageable paging / size constraint
     */
    Page<MidasRunEntity> findByStatusAndNeedsRefactoringTrueOrderByUpdatedAtAsc(
            String status, Pageable pageable);

    List<MidasRunEntity> findByStatusNotInAndUpdatedAtBefore(List<String> statuses, Instant updatedAt);
}

package com.midas.d3.persistence;

import com.midas.d3.persistence.entity.MidasAgentLogEntity;
import com.midas.d3.persistence.entity.MidasRunEntity;
import com.midas.d3.persistence.repository.MidasAgentLogRepository;
import com.midas.d3.persistence.repository.MidasRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Persistence façade for all MIDAS pipeline database operations.
 *
 * <h2>Resilience contract</h2>
 * Every public method is <em>non-throwing</em>: any {@link Exception} thrown by the
 * underlying repository is caught, logged as a {@code WARN}, and swallowed. This
 * guarantees that a database failure (connectivity issue, schema mismatch, etc.)
 * never propagates into the pipeline state machine and kills an in-flight run.
 *
 * <h2>Transaction management</h2>
 * Each method runs in its own transaction ({@code REQUIRED} propagation inherited from
 * the class-level {@link Transactional} annotation). Caller code (state machine actions,
 * orchestrator) is not transactional, so every call begins a fresh transaction that is
 * committed or rolled back before the method returns.
 *
 * <h2>Bulk-update queries</h2>
 * Status and idea updates use JPQL {@code UPDATE} statements (via
 * {@link MidasRunRepository#updateStatus} etc.) to avoid loading the full entity just
 * to mutate a single column. All bulk updates are marked {@code @Modifying} and run
 * inside a write transaction.
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class PersistenceService {

    private final MidasRunRepository      runRepository;
    private final MidasAgentLogRepository agentLogRepository;

    // ── Run lifecycle ─────────────────────────────────────────────────────────

    /**
     * Inserts a new {@code midas_run} record when a pipeline is started.
     * Called from {@link com.midas.d3.statemachine.PipelineOrchestrator#startPipeline}
     * (and its variants) before the state machine processes any events.
     *
     * @param runId       pipeline run ID (UUID string)
     * @param rawUserIdea the user's original idea text
     * @param chatId      Telegram chat ID, or {@code null} for REST-initiated runs
     */
    public void createRun(String runId, String rawUserIdea, Long chatId) {
        try {
            MidasRunEntity entity = MidasRunEntity.builder()
                    .id(runId)
                    .rawUserIdea(rawUserIdea)
                    .chatId(chatId)
                    .status("STARTED")
                    .build();
            runRepository.save(entity);
            log.debug("[PersistenceService] Created run record [{}].", runId);
        } catch (Exception ex) {
            log.warn("[PersistenceService] Failed to create run record [{}]: {}", runId, ex.getMessage(), ex);
        }
    }

    /**
     * Updates {@code raw_user_idea} after a Human-in-the-Loop clarification.
     * Called from {@link com.midas.d3.statemachine.PipelineOrchestrator#userReplied}.
     *
     * @param runId          the pipeline run ID
     * @param enrichedIdea   the original idea enriched with the user's reply
     */
    public void updateRunIdea(String runId, String enrichedIdea) {
        try {
            runRepository.updateRawUserIdea(runId, enrichedIdea, Instant.now());
            log.debug("[PersistenceService] Updated rawUserIdea for run [{}].", runId);
        } catch (Exception ex) {
            log.warn("[PersistenceService] Failed to update rawUserIdea for run [{}]: {}", runId, ex.getMessage(), ex);
        }
    }

    /**
     * Updates the {@code status} column to reflect the current pipeline stage.
     * Called from {@link com.midas.d3.statemachine.action.AgentEntryAction} on each
     * state entry (e.g. {@code "SYSTEM_ANALYSIS"}, {@code "CODE_GENERATION"}).
     *
     * @param runId  the pipeline run ID
     * @param status new status string (typically the {@code MidasState.name()})
     */
    public void updateRunStatus(String runId, String status) {
        try {
            runRepository.updateStatus(runId, status, Instant.now());
            log.debug("[PersistenceService] Updated status → [{}] for run [{}].", status, runId);
        } catch (Exception ex) {
            log.warn("[PersistenceService] Failed to update status for run [{}]: {}", runId, ex.getMessage(), ex);
        }
    }

    /**
     * Persists the artifact ZIP path, sets status to {@code "COMPLETED"}, and queues
     * the run for Evolution Agent analysis by setting {@code needs_refactoring = true}.
     * Called from {@link com.midas.d3.statemachine.action.PipelineCompletionAction}
     * after the artifacts archive has been created.
     *
     * @param runId        the pipeline run ID
     * @param artifactPath absolute filesystem path to the generated ZIP file
     */
    public void completeRun(String runId, String artifactPath) {
        try {
            Instant now = Instant.now();
            runRepository.updateArtifactPathAndStatus(runId, artifactPath, "COMPLETED", now);
            runRepository.updateNeedsRefactoring(runId, true, now);
            log.info("[PersistenceService] Marked run [{}] COMPLETED, artifact=[{}], queued for evolution.",
                    runId, artifactPath);
        } catch (Exception ex) {
            log.warn("[PersistenceService] Failed to complete run [{}]: {}", runId, ex.getMessage(), ex);
        }
    }

    /**
     * Sets status to {@code "COMPLETED"} and queues the run for Evolution Agent analysis
     * ({@code needs_refactoring = true}). Used for REST-mode runs where no ZIP is
     * produced — the artifacts are accessed via the REST context API.
     *
     * @param runId the pipeline run ID
     */
    public void completeRunWithoutArtifact(String runId) {
        try {
            Instant now = Instant.now();
            runRepository.updateStatus(runId, "COMPLETED", now);
            runRepository.updateNeedsRefactoring(runId, true, now);
            log.info("[PersistenceService] Marked run [{}] COMPLETED (no artifact ZIP), queued for evolution.",
                    runId);
        } catch (Exception ex) {
            log.warn("[PersistenceService] Failed to complete run [{}]: {}", runId, ex.getMessage(), ex);
        }
    }

    /**
     * Clears the {@code needs_refactoring} flag after the Evolution Agent has
     * successfully analyzed the run. Prevents the same run from being re-analyzed
     * in subsequent scheduling cycles.
     *
     * @param runId the pipeline run ID
     */
    public void clearNeedsRefactoring(String runId) {
        try {
            runRepository.updateNeedsRefactoring(runId, false, Instant.now());
            log.debug("[PersistenceService] Cleared needs_refactoring flag for run [{}].", runId);
        } catch (Exception ex) {
            log.warn("[PersistenceService] Failed to clear needs_refactoring for run [{}]: {}",
                    runId, ex.getMessage(), ex);
        }
    }

    // ── Agent log ─────────────────────────────────────────────────────────────

    /**
     * Inserts a {@code midas_agent_log} record for a completed (or failed) agent invocation.
     *
     * <p>This method is called from the async agent execution thread in
     * {@link com.midas.d3.statemachine.action.AgentEntryAction}. It uses
     * {@link MidasRunRepository#getReferenceById} to avoid loading the parent entity
     * when only the FK value is needed.
     *
     * @param runId           the pipeline run ID (FK)
     * @param agentType       agent class name / role (e.g. {@code "SystemAnalystAgent"})
     * @param rawOutput       raw LLM response text or error message
     * @param promptTokens    number of prompt tokens consumed (0 if not tracked)
     * @param completionTokens number of completion tokens consumed (0 if not tracked)
     * @param executionTimeMs wall-clock time of {@code agent.execute()} in milliseconds
     * @param isError         {@code true} if the agent threw an exception
     */
    public void logAgentExecution(String runId,
                                  String agentType,
                                  String rawOutput,
                                  int promptTokens,
                                  int completionTokens,
                                  long executionTimeMs,
                                  boolean isError) {
        try {
            MidasRunEntity runRef = runRepository.getReferenceById(runId);
            MidasAgentLogEntity entry = MidasAgentLogEntity.builder()
                    .run(runRef)
                    .agentType(agentType)
                    .rawOutput(truncate(rawOutput, 10_000))
                    .promptTokens(promptTokens)
                    .completionTokens(completionTokens)
                    .executionTimeMs(executionTimeMs)
                    .isError(isError)
                    .build();
            agentLogRepository.save(entry);
            log.debug("[PersistenceService] Logged agent [{}] execution for run [{}]: {}ms, error={}.",
                    agentType, runId, executionTimeMs, isError);
        } catch (Exception ex) {
            log.warn("[PersistenceService] Failed to log agent [{}] for run [{}]: {}",
                    agentType, runId, ex.getMessage(), ex);
        }
    }

    // ── Internal helpers ─────────────────────────────────────────────────────

    /**
     * Truncates a string to {@code maxLength} characters to prevent oversized
     * TEXT column writes for LLM outputs with very large code generation results.
     */
    private static String truncate(String value, int maxLength) {
        if (value == null) return null;
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "…[truncated]";
    }
}

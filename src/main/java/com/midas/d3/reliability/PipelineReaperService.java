package com.midas.d3.reliability;

import com.midas.d3.persistence.PersistenceService;
import com.midas.d3.persistence.entity.MidasRunEntity;
import com.midas.d3.persistence.repository.MidasRunRepository;
import com.midas.d3.statemachine.PipelineOrchestrator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class PipelineReaperService {

    static final String ORPHAN_MESSAGE =
            "Pipeline timed out or unexpectedly terminated. Run lost — please resubmit.";

    // Terminal run statuses that must never be reaped. COMPLETED_WITH_GAPS is the graceful-degradation
    // terminal: a run that honestly delivered a partial artifact must stay terminal, otherwise the reaper
    // would flip a delivered (degraded) product to ERROR after the stale timeout — a client-visible crash.
    private static final List<String> TERMINAL_STATUSES = List.of("COMPLETED", "COMPLETED_WITH_GAPS", "ERROR");

    private final MidasRunRepository runRepository;
    private final PersistenceService persistenceService;
    private final PipelineOrchestrator orchestrator;

    @Value("${midas.reaper.stale-timeout-minutes:60}")
    private long staleTimeoutMinutes;

    @Scheduled(cron = "${midas.reaper.cron:0 */15 * * * *}")
    public void reapStaleRuns() {
        runReaperCycle();
    }

    public int runReaperCycle() {
        Instant cutoff = Instant.now().minus(Duration.ofMinutes(staleTimeoutMinutes));
        List<MidasRunEntity> candidates =
                runRepository.findByStatusNotInAndUpdatedAtBefore(TERMINAL_STATUSES, cutoff);

        int reaped = 0;
        for (MidasRunEntity run : candidates) {
            if (orchestrator.hasActiveMachine(run.getId())) {
                log.debug("[PipelineReaperService] Skipping run [{}] — in-memory state machine still active.",
                        run.getId());
                continue;
            }
            persistenceService.failOrphanedRun(run.getId(), ORPHAN_MESSAGE, run.getStatus());
            reaped++;
        }

        if (reaped > 0) {
            log.info("[PipelineReaperService] Reaped {} stale pipeline run(s).", reaped);
        }
        return reaped;
    }
}

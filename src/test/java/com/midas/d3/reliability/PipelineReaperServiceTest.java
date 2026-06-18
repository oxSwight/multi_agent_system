package com.midas.d3.reliability;

import com.midas.d3.persistence.PersistenceService;
import com.midas.d3.persistence.entity.MidasRunEntity;
import com.midas.d3.persistence.repository.MidasRunRepository;
import com.midas.d3.statemachine.PipelineOrchestrator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PipelineReaperService — unit tests")
class PipelineReaperServiceTest {

    @Mock private MidasRunRepository runRepository;
    @Mock private PersistenceService persistenceService;
    @Mock private PipelineOrchestrator orchestrator;

    private PipelineReaperService service;

    @BeforeEach
    void setUp() {
        service = new PipelineReaperService(runRepository, persistenceService, orchestrator);
        ReflectionTestUtils.setField(service, "staleTimeoutMinutes", 60L);
    }

    @Nested
    @DisplayName("runReaperCycle()")
    class RunReaperCycleTests {

        @Test
        @DisplayName("reaps stale non-terminal runs without active in-memory machines")
        void reapsStaleRuns_withoutActiveMachine() {
            MidasRunEntity stale = MidasRunEntity.builder()
                    .id("stale-run-001")
                    .status("STARTED")
                    .updatedAt(Instant.now().minusSeconds(7200))
                    .build();
            when(runRepository.findByStatusNotInAndUpdatedAtBefore(
                    eq(List.of("COMPLETED", "ERROR")), any(Instant.class)))
                    .thenReturn(List.of(stale));
            when(orchestrator.hasActiveMachine("stale-run-001")).thenReturn(false);

            int reaped = service.runReaperCycle();

            assertThat(reaped).isEqualTo(1);
            verify(persistenceService).failOrphanedRun(
                    "stale-run-001",
                    PipelineReaperService.ORPHAN_MESSAGE,
                    "STARTED");
        }

        @Test
        @DisplayName("skips stale runs that still have an active in-memory state machine")
        void skipsRuns_withActiveMachine() {
            MidasRunEntity stale = MidasRunEntity.builder()
                    .id("active-run-001")
                    .status("CODE_GENERATION")
                    .updatedAt(Instant.now().minusSeconds(7200))
                    .build();
            when(runRepository.findByStatusNotInAndUpdatedAtBefore(
                    eq(List.of("COMPLETED", "ERROR")), any(Instant.class)))
                    .thenReturn(List.of(stale));
            when(orchestrator.hasActiveMachine("active-run-001")).thenReturn(true);

            int reaped = service.runReaperCycle();

            assertThat(reaped).isZero();
            verify(persistenceService, never()).failOrphanedRun(any(), any(), any());
        }

        @Test
        @DisplayName("returns zero and performs no updates when no stale candidates exist")
        void noOp_whenQueueEmpty() {
            when(runRepository.findByStatusNotInAndUpdatedAtBefore(
                    eq(List.of("COMPLETED", "ERROR")), any(Instant.class)))
                    .thenReturn(List.of());

            int reaped = service.runReaperCycle();

            assertThat(reaped).isZero();
            verifyNoInteractions(persistenceService);
        }

        @Test
        @DisplayName("reaps in-progress stage names, not only STARTED")
        void reapsInProgressStage() {
            MidasRunEntity stale = MidasRunEntity.builder()
                    .id("stale-secops")
                    .status("SECOPS_AUDIT")
                    .updatedAt(Instant.now().minusSeconds(7200))
                    .build();
            when(runRepository.findByStatusNotInAndUpdatedAtBefore(
                    eq(List.of("COMPLETED", "ERROR")), any(Instant.class)))
                    .thenReturn(List.of(stale));
            when(orchestrator.hasActiveMachine("stale-secops")).thenReturn(false);

            int reaped = service.runReaperCycle();

            assertThat(reaped).isEqualTo(1);
            verify(persistenceService).failOrphanedRun(
                    "stale-secops",
                    PipelineReaperService.ORPHAN_MESSAGE,
                    "SECOPS_AUDIT");
        }
    }
}

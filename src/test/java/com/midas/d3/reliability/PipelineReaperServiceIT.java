package com.midas.d3.reliability;

import com.midas.d3.persistence.entity.MidasAgentLogEntity;
import com.midas.d3.persistence.entity.MidasRunEntity;
import com.midas.d3.persistence.repository.MidasAgentLogRepository;
import com.midas.d3.persistence.repository.MidasRunRepository;
import com.midas.d3.statemachine.PipelineOrchestrator;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DisplayName("PipelineReaperService — Spring integration")
class PipelineReaperServiceIT {

    @Autowired
    private PipelineReaperService reaperService;

    @Autowired
    private MidasRunRepository runRepository;

    @Autowired
    private MidasAgentLogRepository agentLogRepository;

    @Autowired
    private EntityManager entityManager;

    @MockBean
    private PipelineOrchestrator orchestrator;

    @Test
    @DisplayName("runReaperCycle() marks expired STARTED runs as ERROR with audit log entry")
    void reaperCycle_marksExpiredRunAsError() {
        String runId = "reaper-it-stale-001";
        runRepository.save(MidasRunEntity.builder()
                .id(runId)
                .rawUserIdea("Build a stuck pipeline test")
                .status("STARTED")
                .build());
        runRepository.updateStatus(runId, "STARTED", Instant.now().minus(Duration.ofHours(2)));

        when(orchestrator.hasActiveMachine(runId)).thenReturn(false);

        int reaped = reaperService.runReaperCycle();

        assertThat(reaped).isEqualTo(1);
        entityManager.flush();
        entityManager.clear();
        MidasRunEntity updated = runRepository.findById(runId).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo("ERROR");

        List<MidasAgentLogEntity> logs = agentLogRepository.findByRunId(runId);
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getAgentType()).isEqualTo("PipelineReaper");
        assertThat(logs.get(0).isError()).isTrue();
        assertThat(logs.get(0).getRawOutput()).contains(PipelineReaperService.ORPHAN_MESSAGE);
        assertThat(logs.get(0).getRawOutput()).contains("previous status: STARTED");
    }

    @Test
    @DisplayName("runReaperCycle() ignores fresh STARTED runs")
    void reaperCycle_ignoresFreshRun() {
        String runId = "reaper-it-fresh-001";
        runRepository.save(MidasRunEntity.builder()
                .id(runId)
                .rawUserIdea("Fresh run")
                .status("STARTED")
                .build());

        when(orchestrator.hasActiveMachine(runId)).thenReturn(false);

        int reaped = reaperService.runReaperCycle();

        assertThat(reaped).isZero();
        assertThat(runRepository.findById(runId).orElseThrow().getStatus()).isEqualTo("STARTED");
        assertThat(agentLogRepository.findByRunId(runId)).isEmpty();
    }

    @Test
    @DisplayName("PipelineReaperService bean is loaded by Spring context")
    void beanIsPresent() {
        assertThat(reaperService).isNotNull();
        assertThatNoException().isThrownBy(() -> reaperService.runReaperCycle());
    }
}

package com.midas.d3.persistence;

import com.midas.d3.persistence.entity.MidasAgentLogEntity;
import com.midas.d3.persistence.entity.MidasRunEntity;
import com.midas.d3.persistence.repository.MidasAgentLogRepository;
import com.midas.d3.persistence.repository.MidasRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Repository slice tests for {@link MidasAgentLogRepository}.
 *
 * <p>Uses {@link DataJpaTest} with H2 in-memory database. Each test is wrapped
 * in a transaction that is rolled back after the test, ensuring full isolation.
 *
 * <p>A shared {@link MidasRunEntity} is created in {@link #setUp()} and flushed
 * to the DB so FK constraints are satisfied when saving log entries.
 */
@DataJpaTest
@DisplayName("MidasAgentLogRepository — JPA slice tests")
class MidasAgentLogRepositoryTest {

    @Autowired
    private MidasRunRepository runRepository;

    @Autowired
    private MidasAgentLogRepository agentLogRepository;

    @Autowired
    private TestEntityManager em;

    private MidasRunEntity parentRun;

    @BeforeEach
    void setUp() {
        parentRun = runRepository.save(MidasRunEntity.builder()
                .id("log-parent-run-001")
                .rawUserIdea("Test agent logging across stages")
                .status("SYSTEM_ANALYSIS")
                .build());
        em.flush();
    }

    // ── Save and find by runId ─────────────────────────────────────────────────

    @Test
    @DisplayName("save() inserts log with UUID PK; findByRunId() retrieves it")
    void save_persistsLog_findByRunIdReturnsIt() {
        MidasAgentLogEntity logEntry = MidasAgentLogEntity.builder()
                .run(parentRun)
                .agentType("SystemAnalystAgent")
                .rawOutput("{\"spec\":\"Technical specification content\"}")
                .executionTimeMs(1_500L)
                .isError(false)
                .build();
        agentLogRepository.save(logEntry);
        em.flush();
        em.clear();

        List<MidasAgentLogEntity> logs = agentLogRepository.findByRunId("log-parent-run-001");

        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getAgentType()).isEqualTo("SystemAnalystAgent");
        assertThat(logs.get(0).getExecutionTimeMs()).isEqualTo(1_500L);
        assertThat(logs.get(0).isError()).isFalse();
        assertThat(logs.get(0).getId()).isNotNull();
        assertThat(logs.get(0).getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("findByRunId() orders results by createdAt ASC (pipeline stage order)")
    void findByRunId_returnsLogsInChronologicalOrder() {
        agentLogRepository.save(MidasAgentLogEntity.builder()
                .run(parentRun).agentType("SystemAnalystAgent").executionTimeMs(100L).build());
        em.flush();
        agentLogRepository.save(MidasAgentLogEntity.builder()
                .run(parentRun).agentType("SoftwareArchitectAgent").executionTimeMs(200L).build());
        em.flush();
        agentLogRepository.save(MidasAgentLogEntity.builder()
                .run(parentRun).agentType("ImplementationEngineerAgent").executionTimeMs(300L).build());
        em.flush();
        em.clear();

        List<MidasAgentLogEntity> logs = agentLogRepository.findByRunId("log-parent-run-001");

        assertThat(logs).hasSize(3);
        assertThat(logs.get(0).getAgentType()).isEqualTo("SystemAnalystAgent");
        assertThat(logs.get(2).getAgentType()).isEqualTo("ImplementationEngineerAgent");
    }

    // ── Error filtering ────────────────────────────────────────────────────────

    @Test
    @DisplayName("findByRunIdAndIsError(true) returns only error entries")
    void findByRunIdAndIsError_returnsOnlyErrors() {
        agentLogRepository.save(MidasAgentLogEntity.builder()
                .run(parentRun).agentType("SystemAnalystAgent").isError(false).executionTimeMs(100L).build());
        agentLogRepository.save(MidasAgentLogEntity.builder()
                .run(parentRun).agentType("ImplementationEngineerAgent").isError(true)
                .rawOutput("LLM timeout after 3 retries").executionTimeMs(9_000L).build());
        agentLogRepository.save(MidasAgentLogEntity.builder()
                .run(parentRun).agentType("QaAutomationAgent").isError(false).executionTimeMs(800L).build());
        em.flush();
        em.clear();

        List<MidasAgentLogEntity> errors = agentLogRepository.findByRunIdAndIsError("log-parent-run-001", true);
        List<MidasAgentLogEntity> successes = agentLogRepository.findByRunIdAndIsError("log-parent-run-001", false);

        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).getAgentType()).isEqualTo("ImplementationEngineerAgent");
        assertThat(errors.get(0).getRawOutput()).contains("timeout");

        assertThat(successes).hasSize(2);
    }

    // ── Count queries ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("countByRunId() returns correct count after saving multiple logs")
    void countByRunId_returnsCorrectCount() {
        for (int i = 0; i < 6; i++) {
            agentLogRepository.save(MidasAgentLogEntity.builder()
                    .run(parentRun)
                    .agentType("Agent_" + i)
                    .executionTimeMs(100L * i)
                    .build());
        }
        em.flush();
        em.clear();

        long count = agentLogRepository.countByRunId("log-parent-run-001");
        assertThat(count).isEqualTo(6);
    }

    @Test
    @DisplayName("countByRunId() returns 0 for a run with no log entries")
    void countByRunId_noLogs_returnsZero() {
        MidasRunEntity emptyRun = runRepository.save(MidasRunEntity.builder()
                .id("empty-run-001")
                .rawUserIdea("Empty run")
                .status("STARTED")
                .build());
        em.flush();
        em.clear();

        long count = agentLogRepository.countByRunId("empty-run-001");
        assertThat(count).isZero();
    }

    // ── Default field values ───────────────────────────────────────────────────

    @Test
    @DisplayName("@Builder.Default values (0 tokens, false isError) are persisted correctly")
    void save_defaultValues_arePersistedCorrectly() {
        agentLogRepository.save(MidasAgentLogEntity.builder()
                .run(parentRun)
                .agentType("SecOpsAgent")
                .rawOutput("{\"audit\":\"passed\"}")
                .promptTokens(512)
                .completionTokens(256)
                .executionTimeMs(3_200L)
                .isError(false)
                .build());
        em.flush();
        em.clear();

        List<MidasAgentLogEntity> logs = agentLogRepository.findByRunId("log-parent-run-001");
        assertThat(logs).hasSize(1);
        MidasAgentLogEntity log = logs.get(0);
        assertThat(log.getPromptTokens()).isEqualTo(512);
        assertThat(log.getCompletionTokens()).isEqualTo(256);
        assertThat(log.getExecutionTimeMs()).isEqualTo(3_200L);
        assertThat(log.isError()).isFalse();
    }

    // ── Performance statistics ─────────────────────────────────────────────────

    @Test
    @DisplayName("findPerformanceStatsByAgentType() aggregates timing data correctly")
    void findPerformanceStatsByAgentType_aggregatesCorrectly() {
        agentLogRepository.save(MidasAgentLogEntity.builder()
                .run(parentRun).agentType("SystemAnalystAgent").executionTimeMs(1_000L).isError(false).build());
        agentLogRepository.save(MidasAgentLogEntity.builder()
                .run(parentRun).agentType("SystemAnalystAgent").executionTimeMs(2_000L).isError(false).build());
        agentLogRepository.save(MidasAgentLogEntity.builder()
                .run(parentRun).agentType("ImplementationEngineerAgent").executionTimeMs(5_000L).isError(false).build());
        // Error entry — excluded from performance stats
        agentLogRepository.save(MidasAgentLogEntity.builder()
                .run(parentRun).agentType("SystemAnalystAgent").executionTimeMs(500L).isError(true).build());
        em.flush();
        em.clear();

        List<Object[]> stats = agentLogRepository.findPerformanceStatsByAgentType();

        assertThat(stats).hasSize(2);
        // Results ordered by avg DESC → ImplementationEngineerAgent (5000ms avg) first
        Object[] backendStats = stats.get(0);
        assertThat(backendStats[0]).isEqualTo("ImplementationEngineerAgent");
        assertThat(((Number) backendStats[3]).longValue()).isEqualTo(1L); // count

        Object[] analystStats = stats.get(1);
        assertThat(analystStats[0]).isEqualTo("SystemAnalystAgent");
        assertThat(((Number) analystStats[3]).longValue()).isEqualTo(2L); // count (error excluded)
        // avg of 1000+2000 = 1500ms
        assertThat(((Number) analystStats[1]).doubleValue()).isEqualTo(1500.0);
    }
}

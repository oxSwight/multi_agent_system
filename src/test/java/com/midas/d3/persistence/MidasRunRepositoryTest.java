package com.midas.d3.persistence;

import com.midas.d3.persistence.entity.MidasRunEntity;
import com.midas.d3.persistence.repository.MidasRunRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Repository slice tests for {@link MidasRunRepository}.
 *
 * <p>Uses {@link DataJpaTest} which:
 * <ul>
 *   <li>Configures an H2 in-memory database (auto-configured)</li>
 *   <li>Creates the schema from JPA entity annotations ({@code ddl-auto=create-drop})</li>
 *   <li>Does NOT load Flyway, Telegram, LLM, or other non-JPA beans</li>
 *   <li>Wraps each test in a transaction that is rolled back on completion</li>
 * </ul>
 */
@DataJpaTest
@DisplayName("MidasRunRepository — JPA slice tests")
class MidasRunRepositoryTest {

    @Autowired
    private MidasRunRepository runRepository;

    @Autowired
    private TestEntityManager em;

    // ── Save and find ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("save() inserts record; findById() retrieves it with auto-populated timestamps")
    void save_persistsRecord_findByIdReturnsIt() {
        MidasRunEntity run = MidasRunEntity.builder()
                .id("run-save-001")
                .rawUserIdea("Build a REST API for task management")
                .chatId(null)
                .status("STARTED")
                .build();

        runRepository.save(run);
        em.flush();
        em.clear();

        Optional<MidasRunEntity> found = runRepository.findById("run-save-001");

        assertThat(found).isPresent();
        assertThat(found.get().getRawUserIdea()).isEqualTo("Build a REST API for task management");
        assertThat(found.get().getStatus()).isEqualTo("STARTED");
        assertThat(found.get().getTotalPromptTokens()).isZero();
        assertThat(found.get().getTotalCompletionTokens()).isZero();
        assertThat(found.get().isNeedsRefactoring()).isFalse();
        assertThat(found.get().getCreatedAt()).isNotNull();
        assertThat(found.get().getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("save() with Telegram chatId stores chatId; findByChatId returns the run")
    void save_withChatId_findByChatIdReturnsRun() {
        runRepository.save(MidasRunEntity.builder()
                .id("run-chat-001")
                .rawUserIdea("Build a Telegram bot")
                .chatId(987654321L)
                .status("STARTED")
                .build());
        em.flush();
        em.clear();

        List<MidasRunEntity> found = runRepository.findByChatIdOrderByCreatedAtDesc(987654321L);

        assertThat(found).hasSize(1);
        assertThat(found.get(0).getChatId()).isEqualTo(987654321L);
    }

    // ── Bulk update: status ────────────────────────────────────────────────────

    @Test
    @DisplayName("updateStatus() changes status without reloading the full entity")
    void updateStatus_changesStatusColumn() {
        runRepository.save(MidasRunEntity.builder()
                .id("run-status-001")
                .rawUserIdea("Build a microservice")
                .status("STARTED")
                .build());
        em.flush();
        em.clear();

        runRepository.updateStatus("run-status-001", "SYSTEM_ANALYSIS", Instant.now());
        em.flush();
        em.clear();

        MidasRunEntity updated = runRepository.findById("run-status-001").orElseThrow();
        assertThat(updated.getStatus()).isEqualTo("SYSTEM_ANALYSIS");
    }

    @Test
    @DisplayName("updateStatus() to COMPLETED reflects in findByStatus query")
    void updateStatus_toCompleted_foundByStatusQuery() {
        runRepository.save(MidasRunEntity.builder()
                .id("run-status-002")
                .rawUserIdea("Build a webapp")
                .status("STARTED")
                .build());
        em.flush();
        em.clear();

        runRepository.updateStatus("run-status-002", "COMPLETED", Instant.now());
        em.flush();
        em.clear();

        List<MidasRunEntity> completed = runRepository.findByStatusOrderByCreatedAtDesc("COMPLETED");
        assertThat(completed).extracting(MidasRunEntity::getId).contains("run-status-002");
    }

    // ── Bulk update: rawUserIdea ───────────────────────────────────────────────

    @Test
    @DisplayName("updateRawUserIdea() persists enriched clarification text")
    void updateRawUserIdea_persistsEnrichedText() {
        runRepository.save(MidasRunEntity.builder()
                .id("run-idea-001")
                .rawUserIdea("Build an e-commerce app")
                .status("WAITING_FOR_USER_INPUT")
                .build());
        em.flush();
        em.clear();

        String enriched = "Build an e-commerce app\n\n[УТОЧНЕНИЕ ОТ ПОЛЬЗОВАТЕЛЯ]\nFocus on mobile.";
        runRepository.updateRawUserIdea("run-idea-001", enriched, Instant.now());
        em.flush();
        em.clear();

        MidasRunEntity updated = runRepository.findById("run-idea-001").orElseThrow();
        assertThat(updated.getRawUserIdea()).isEqualTo(enriched);
    }

    // ── Bulk update: artifact path + status ───────────────────────────────────

    @Test
    @DisplayName("updateArtifactPathAndStatus() stores ZIP path and sets COMPLETED status atomically")
    void updateArtifactPathAndStatus_storesPathAndStatus() {
        runRepository.save(MidasRunEntity.builder()
                .id("run-artifact-001")
                .rawUserIdea("Build a full-stack app")
                .status("SECOPS_AUDIT")
                .build());
        em.flush();
        em.clear();

        runRepository.updateArtifactPathAndStatus(
                "run-artifact-001",
                "/tmp/midas_result_run-artifact-001.zip",
                "COMPLETED",
                Instant.now());
        em.flush();
        em.clear();

        MidasRunEntity completed = runRepository.findById("run-artifact-001").orElseThrow();
        assertThat(completed.getStatus()).isEqualTo("COMPLETED");
        assertThat(completed.getArtifactPath()).isEqualTo("/tmp/midas_result_run-artifact-001.zip");
    }

    // ── Non-existent ID ────────────────────────────────────────────────────────

    @Test
    @DisplayName("findById() with unknown ID returns empty Optional")
    void findById_unknownId_returnsEmpty() {
        Optional<MidasRunEntity> result = runRepository.findById("does-not-exist");
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("findByStatusNotInAndUpdatedAtBefore() returns only stale non-terminal runs")
    void findByStatusNotInAndUpdatedAtBefore_returnsStaleNonTerminalRuns() {
        Instant staleTime = Instant.now().minusSeconds(7200);

        runRepository.save(MidasRunEntity.builder()
                .id("repo-stale-started")
                .rawUserIdea("Stale started")
                .status("STARTED")
                .build());
        runRepository.save(MidasRunEntity.builder()
                .id("repo-fresh-started")
                .rawUserIdea("Fresh started")
                .status("STARTED")
                .build());
        runRepository.save(MidasRunEntity.builder()
                .id("repo-stale-code")
                .rawUserIdea("Stale code gen")
                .status("CODE_GENERATION")
                .build());
        runRepository.save(MidasRunEntity.builder()
                .id("repo-completed")
                .rawUserIdea("Completed")
                .status("COMPLETED")
                .build());
        em.flush();

        runRepository.updateStatus("repo-stale-started", "STARTED", staleTime);
        runRepository.updateStatus("repo-stale-code", "CODE_GENERATION", staleTime);
        runRepository.updateStatus("repo-completed", "COMPLETED", staleTime);

        em.flush();
        em.clear();

        Instant cutoff = Instant.now().minusSeconds(3600);
        List<MidasRunEntity> stale = runRepository.findByStatusNotInAndUpdatedAtBefore(
                List.of("COMPLETED", "ERROR"), cutoff);

        assertThat(stale).extracting(MidasRunEntity::getId)
                .containsExactlyInAnyOrder("repo-stale-started", "repo-stale-code");
    }

    // ── Default field values ───────────────────────────────────────────────────

    @Test
    @DisplayName("@Builder.Default values (0 tokens, false needsRefactoring) are persisted correctly")
    void save_defaultValues_arePersistedCorrectly() {
        runRepository.save(MidasRunEntity.builder()
                .id("run-defaults-001")
                .rawUserIdea("Test default fields")
                .status("STARTED")
                .totalPromptTokens(42)
                .totalCompletionTokens(17)
                .needsRefactoring(true)
                .build());
        em.flush();
        em.clear();

        MidasRunEntity found = runRepository.findById("run-defaults-001").orElseThrow();
        assertThat(found.getTotalPromptTokens()).isEqualTo(42);
        assertThat(found.getTotalCompletionTokens()).isEqualTo(17);
        assertThat(found.isNeedsRefactoring()).isTrue();
    }
}

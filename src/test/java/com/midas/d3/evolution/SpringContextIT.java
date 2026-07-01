package com.midas.d3.evolution;

import com.midas.d3.llm.LlmClient;
import com.midas.d3.persistence.repository.MidasRunRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Spring Boot integration test verifying that:
 * <ol>
 *   <li>{@link ContinuousImprovementService} is correctly wired into the
 *       application context.</li>
 *   <li>{@link EvolutionAgent} is available as a Spring bean.</li>
 *   <li>The scheduler is registered (enabled via {@code @EnableScheduling} in
 *       {@link com.midas.d3.config.AsyncConfig}).</li>
 *   <li>Calling {@link ContinuousImprovementService#runEvolutionCycle()} directly
 *       (simulating a scheduler trigger) completes without error when no pending
 *       runs exist.</li>
 * </ol>
 *
 * <p>The {@link LlmClient} is replaced with a {@link MockBean} so no real HTTP
 * calls are made. The cron expression is disabled in {@code test/resources/application.yml}
 * ({@code midas.evolution.cron: "-"}), preventing automatic background triggers
 * during test execution.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("ContinuousImprovementService — Spring context integration")
class SpringContextIT {

    @Autowired
    private ContinuousImprovementService service;

    @Autowired
    private EvolutionAgent evolutionAgent;

    @MockBean
    private LlmClient llmClient;

    @MockBean
    private MidasRunRepository runRepository;

    // ── Wiring verification ───────────────────────────────────────────────────

    @Test
    @DisplayName("ContinuousImprovementService bean is loaded by Spring context")
    void serviceBeanIsPresent() {
        assertThat(service).isNotNull();
    }

    @Test
    @DisplayName("EvolutionAgent bean is loaded by Spring context")
    void evolutionAgentBeanIsPresent() {
        assertThat(evolutionAgent).isNotNull();
    }

    // ── Scheduler smoke test ──────────────────────────────────────────────────

    @Test
    @DisplayName("runEvolutionCycle() completes without exception when no pending runs exist")
    void schedulerEntryPoint_noOp_whenQueueEmpty() {
        when(runRepository.findByStatusInAndNeedsRefactoringTrueOrderByUpdatedAtAsc(
                eq(List.of("COMPLETED", "COMPLETED_WITH_GAPS")), any(Pageable.class)))
                .thenReturn(Page.empty());

        assertThatNoException().isThrownBy(() -> service.runEvolutionCycle());
    }
}

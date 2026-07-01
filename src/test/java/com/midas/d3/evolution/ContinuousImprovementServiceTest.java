package com.midas.d3.evolution;

import com.midas.d3.llm.LlmCallException;
import com.midas.d3.llm.LlmClient;
import com.midas.d3.persistence.PersistenceService;
import com.midas.d3.persistence.entity.MidasAgentLogEntity;
import com.midas.d3.persistence.entity.MidasRunEntity;
import com.midas.d3.persistence.repository.MidasAgentLogRepository;
import com.midas.d3.persistence.repository.MidasEvolutionLogRepository;
import com.midas.d3.persistence.repository.MidasRunRepository;
import com.midas.d3.telegram.TelegramPipelineBot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.io.File;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ContinuousImprovementService}.
 *
 * <p>All dependencies are mocked with Mockito so no database or LLM connection
 * is needed. The scheduling infrastructure ({@code @Scheduled}) is not exercised
 * here — {@link #runEvolutionCycle()} is called directly.
 *
 * <p>Integration wiring (Spring context, scheduler registration, bean availability)
 * is verified in {@link SpringContextIT}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ContinuousImprovementService — unit tests")
class ContinuousImprovementServiceTest {

    // ── Mocks ─────────────────────────────────────────────────────────────────

    @Mock private MidasRunRepository                  runRepository;
    @Mock private MidasAgentLogRepository             logRepository;
    @Mock private EvolutionAgent                      evolutionAgent;
    @Mock private PersistenceService                  persistenceService;
    @Mock private ObjectProvider<TelegramPipelineBot> telegramBotProvider;
    @Mock private TelegramPipelineBot                 telegramBot;
    @Mock private MidasEvolutionLogRepository         evolutionLogRepository;

    private ContinuousImprovementService service;

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private static final String RUN_ID   = "run-evolution-001";
    private static final long   CHAT_ID  = 123_456_789L;
    private static final String RAW_IDEA = "Build a task management microservice";
    private static final String REPORT   = "# MIDAS Evolution Report\n\nAll looks good!";

    @BeforeEach
    void setUp() {
        service = new ContinuousImprovementService(
                runRepository, logRepository, evolutionAgent,
                persistenceService, telegramBotProvider, evolutionLogRepository);
    }

    // ── runEvolutionCycle — happy path ────────────────────────────────────────

    @Nested
    @DisplayName("runEvolutionCycle()")
    class RunEvolutionCycleTests {

        @Test
        @DisplayName("processes the oldest pending run and clears the flag")
        void processesOldestPendingRun() {
            MidasRunEntity run = buildRun(CHAT_ID);
            givenPendingRun(run);
            givenLogs(run, List.of(buildLog(run, "ImplementationEngineerAgent", "class Foo {}")));
            when(evolutionAgent.analyzeCode(anyString(), eq(RUN_ID))).thenReturn(REPORT);
            when(telegramBotProvider.getIfAvailable()).thenReturn(telegramBot);

            service.runEvolutionCycle();

            verify(evolutionAgent).analyzeCode(anyString(), eq(RUN_ID));
            verify(persistenceService).clearNeedsRefactoring(RUN_ID);
        }

        @Test
        @DisplayName("does nothing when no pending runs exist")
        void doesNothingWhenNoPendingRuns() {
            when(runRepository.findByStatusInAndNeedsRefactoringTrueOrderByUpdatedAtAsc(
                    eq(List.of("COMPLETED", "COMPLETED_WITH_GAPS")), any(Pageable.class)))
                    .thenReturn(Page.empty());

            service.runEvolutionCycle();

            verifyNoInteractions(evolutionAgent, persistenceService, logRepository);
        }
    }

    // ── processRun ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("processRun()")
    class ProcessRunTests {

        @Test
        @DisplayName("sends short report as inline HTML when under TELEGRAM_MAX_TEXT_LENGTH")
        void sendsShortReportInline() {
            MidasRunEntity run = buildRun(CHAT_ID);
            givenLogs(run, List.of(buildLog(run, "ImplementationEngineerAgent", "class Foo {}")));
            when(evolutionAgent.analyzeCode(anyString(), eq(RUN_ID))).thenReturn(REPORT);
            when(telegramBotProvider.getIfAvailable()).thenReturn(telegramBot);

            service.processRun(run);

            ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);
            verify(telegramBot, times(2)).sendHtmlMessage(eq(CHAT_ID), msgCaptor.capture());
            List<String> messages = msgCaptor.getAllValues();
            assertThat(messages.get(0)).contains("Эволюционный анализ завершен");
            assertThat(messages.get(1)).contains(REPORT);
        }

        @Test
        @DisplayName("sends long report as .md document when over TELEGRAM_MAX_TEXT_LENGTH")
        void sendsLongReportAsDocument() {
            MidasRunEntity run = buildRun(CHAT_ID);
            String longReport = "x".repeat(ContinuousImprovementService.TELEGRAM_MAX_TEXT_LENGTH + 1);
            givenLogs(run, List.of(buildLog(run, "ImplementationEngineerAgent", "class Foo {}")));
            when(evolutionAgent.analyzeCode(anyString(), eq(RUN_ID))).thenReturn(longReport);
            when(telegramBotProvider.getIfAvailable()).thenReturn(telegramBot);

            service.processRun(run);

            // 1 summary message + 1 document (no second sendHtmlMessage)
            verify(telegramBot, times(1)).sendHtmlMessage(eq(CHAT_ID), anyString());
            verify(telegramBot, times(1)).sendArtifactDocument(
                    eq(CHAT_ID), any(File.class), anyString());
        }

        @Test
        @DisplayName("skips Telegram when run has no chatId (REST-initiated)")
        void skipsNotificationForRestRuns() {
            MidasRunEntity run = buildRun(null);   // no chatId
            givenLogs(run, List.of(buildLog(run, "ImplementationEngineerAgent", "class Foo {}")));
            when(evolutionAgent.analyzeCode(anyString(), eq(RUN_ID))).thenReturn(REPORT);

            service.processRun(run);

            verifyNoInteractions(telegramBotProvider, telegramBot);
            verify(persistenceService).clearNeedsRefactoring(RUN_ID);
        }

        @Test
        @DisplayName("skips Telegram when bot is not active")
        void skipsNotificationWhenBotUnavailable() {
            MidasRunEntity run = buildRun(CHAT_ID);
            givenLogs(run, List.of(buildLog(run, "ImplementationEngineerAgent", "class Foo {}")));
            when(evolutionAgent.analyzeCode(anyString(), eq(RUN_ID))).thenReturn(REPORT);
            when(telegramBotProvider.getIfAvailable()).thenReturn(null);  // bot disabled

            service.processRun(run);

            verify(telegramBotProvider).getIfAvailable();
            verifyNoInteractions(telegramBot);
            verify(persistenceService).clearNeedsRefactoring(RUN_ID);
        }

        @Test
        @DisplayName("clears flag and skips analysis when no code context is available")
        void clearsFlag_whenNoLogsExist() {
            MidasRunEntity run = buildRun(CHAT_ID);
            when(logRepository.findByRunIdAndIsError(RUN_ID, false)).thenReturn(List.of());

            service.processRun(run);

            verifyNoInteractions(evolutionAgent);
            verify(persistenceService).clearNeedsRefactoring(RUN_ID);
        }

        @Test
        @DisplayName("does not re-throw on LLM failure — cycle must remain alive")
        void doesNotPropagateLlmFailure() {
            MidasRunEntity run = buildRun(CHAT_ID);
            givenLogs(run, List.of(buildLog(run, "ImplementationEngineerAgent", "class Foo {}")));
            when(evolutionAgent.analyzeCode(anyString(), eq(RUN_ID)))
                    .thenThrow(new LlmCallException("LLM down", 503, true));

            assertThatNoException().isThrownBy(() -> service.processRun(run));

            // Flag must NOT be cleared if analysis failed
            verify(persistenceService, never()).clearNeedsRefactoring(anyString());
        }
    }

    // ── Code context content ──────────────────────────────────────────────────

    @Nested
    @DisplayName("Code context assembly")
    class CodeContextTests {

        @Test
        @DisplayName("context includes project idea and all non-error logs")
        void contextContainsIdeaAndAgentOutputs() {
            MidasRunEntity run = buildRun(CHAT_ID);
            MidasAgentLogEntity backendLog = buildLog(run, "ImplementationEngineerAgent", "class Service {}");
            MidasAgentLogEntity secOpsLog  = buildLog(run, "SecOpsAgent", "Dockerfile content");
            givenLogs(run, List.of(backendLog, secOpsLog));

            ArgumentCaptor<String> contextCaptor = ArgumentCaptor.forClass(String.class);
            when(evolutionAgent.analyzeCode(contextCaptor.capture(), eq(RUN_ID))).thenReturn(REPORT);
            when(telegramBotProvider.getIfAvailable()).thenReturn(telegramBot);

            service.processRun(run);

            String context = contextCaptor.getValue();
            assertThat(context).contains(RAW_IDEA);
            assertThat(context).contains("ImplementationEngineerAgent");
            assertThat(context).contains("class Service {}");
            assertThat(context).contains("SecOpsAgent");
            assertThat(context).contains("Dockerfile content");
        }

        @Test
        @DisplayName("ImplementationEngineer output appears before SecOps in context (priority order)")
        void agentPriorityOrdering() {
            MidasRunEntity run = buildRun(CHAT_ID);
            MidasAgentLogEntity secLog     = buildLog(run, "SecOpsAgent",          "sec-content");
            MidasAgentLogEntity backendLog = buildLog(run, "ImplementationEngineerAgent", "backend-content");
            givenLogs(run, List.of(secLog, backendLog));  // reversed order in DB

            ArgumentCaptor<String> contextCaptor = ArgumentCaptor.forClass(String.class);
            when(evolutionAgent.analyzeCode(contextCaptor.capture(), eq(RUN_ID))).thenReturn(REPORT);
            when(telegramBotProvider.getIfAvailable()).thenReturn(telegramBot);

            service.processRun(run);

            String context = contextCaptor.getValue();
            assertThat(context.indexOf("backend-content"))
                    .isLessThan(context.indexOf("sec-content"));
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private MidasRunEntity buildRun(Long chatId) {
        return MidasRunEntity.builder()
                .id(RUN_ID)
                .rawUserIdea(RAW_IDEA)
                .chatId(chatId)
                .status("COMPLETED")
                .needsRefactoring(true)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    private MidasAgentLogEntity buildLog(MidasRunEntity run, String agentType, String rawOutput) {
        return MidasAgentLogEntity.builder()
                .id(UUID.randomUUID())
                .run(run)
                .agentType(agentType)
                .rawOutput(rawOutput)
                .promptTokens(100)
                .completionTokens(200)
                .executionTimeMs(1_500L)
                .isError(false)
                .createdAt(Instant.now())
                .build();
    }

    private void givenPendingRun(MidasRunEntity run) {
        Page<MidasRunEntity> page = new PageImpl<>(List.of(run));
        when(runRepository.findByStatusInAndNeedsRefactoringTrueOrderByUpdatedAtAsc(
                eq(List.of("COMPLETED", "COMPLETED_WITH_GAPS")), any(Pageable.class)))
                .thenReturn(page);
    }

    private void givenLogs(MidasRunEntity run, List<MidasAgentLogEntity> logs) {
        when(logRepository.findByRunIdAndIsError(run.getId(), false)).thenReturn(logs);
    }
}

package com.midas.d3.evolution;

import com.midas.d3.config.AsyncConfig;
import com.midas.d3.persistence.PersistenceService;
import com.midas.d3.persistence.entity.MidasAgentLogEntity;
import com.midas.d3.persistence.entity.MidasEvolutionLogEntity;
import com.midas.d3.persistence.entity.MidasRunEntity;
import com.midas.d3.persistence.repository.MidasAgentLogRepository;
import com.midas.d3.persistence.repository.MidasEvolutionLogRepository;
import com.midas.d3.persistence.repository.MidasRunRepository;
import com.midas.d3.telegram.TelegramPipelineBot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Comparator;
import java.util.List;

/**
 * Background service that continuously improves pipeline-generated projects by
 * submitting completed pipeline runs to the {@link EvolutionAgent} for
 * expert code review.
 *
 * <h2>Scheduling</h2>
 * Runs on a configurable cron expression ({@code midas.evolution.cron},
 * default: {@code 0 0 * * * *} — top of every hour).  In CI and unit tests
 * the cron is set to {@code "-"} which disables the trigger entirely; tests
 * call {@link #runEvolutionCycle()} directly.
 *
 * <h2>Processing loop (per cycle)</h2>
 * <ol>
 *   <li>Query the database for one {@code COMPLETED} or {@code COMPLETED_WITH_GAPS} run with
 *       {@code needs_refactoring = true}, ordered by {@code updated_at ASC} so
 *       the oldest pending run is always processed first.</li>
 *   <li>Assemble a code-review context from all non-error
 *       {@link MidasAgentLogEntity} outputs for that run.</li>
 *   <li>Submit the context to {@link EvolutionAgent} for LLM analysis.</li>
 *   <li>Persist the analysis result and clear the {@code needs_refactoring} flag
 *       so the same run is never re-analyzed.</li>
 *   <li>If the run was Telegram-initiated, notify the user with a summary
 *       message followed by the full Markdown report (as a {@code .md} document
 *       when the report exceeds Telegram's 4 096-character text limit).</li>
 * </ol>
 *
 * <h2>Non-blocking guarantee</h2>
 * The scheduling thread is freed immediately because {@link #runEvolutionCycle()}
 * is annotated with {@code @Async(AGENT_EXECUTOR)}: the actual work runs on the
 * shared {@value AsyncConfig#AGENT_EXECUTOR} thread pool and cannot block the
 * Spring scheduler.
 *
 * <h2>Telegram-optional design</h2>
 * {@link TelegramPipelineBot} is injected via {@link ObjectProvider}, mirroring
 * the pattern used in {@link com.midas.d3.statemachine.action.PipelineCompletionAction}.
 * When the bot is not available (e.g. {@code midas.telegram.enabled=false}), the
 * evolution analysis still runs — only the notification step is skipped.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContinuousImprovementService {

    /**
     * Telegram caps single text messages at 4 096 chars.
     * We stay a little under to leave room for HTML escaping overhead.
     */
    static final int TELEGRAM_MAX_TEXT_LENGTH = 3_800;

    /**
     * Priority order for agent output inclusion in the code review context.
     * ImplementationEngineer code is most actionable; SecOps adds security context;
     * QA shows test quality; Architecture provides design context.
     */
    private static final List<String> AGENT_PRIORITY = List.of(
            "ImplementationEngineerAgent",
            "SecOpsAgent",
            "QaAutomationAgent",
            "SoftwareArchitectAgent",
            "IntegrationEngineerAgent",
            "SystemAnalystAgent"
    );

    /**
     * Terminal statuses whose runs are eligible for evolution analysis. Includes COMPLETED_WITH_GAPS
     * because graceful-degradation runs are flagged {@code needs_refactoring=true} ("queued for
     * evolution") by {@code PersistenceService.completeRunWithGaps*} — a run delivered with known gaps
     * is exactly the kind the improvement loop should analyze.
     */
    private static final List<String> EVOLUTION_ELIGIBLE_STATUSES =
            List.of("COMPLETED", "COMPLETED_WITH_GAPS");

    private final MidasRunRepository              runRepository;
    private final MidasAgentLogRepository         logRepository;
    private final EvolutionAgent                  evolutionAgent;
    private final PersistenceService              persistenceService;
    private final ObjectProvider<TelegramPipelineBot> telegramBotProvider;
    private final MidasEvolutionLogRepository     evolutionLogRepository;

    // ── Scheduler entry point ─────────────────────────────────────────────────

    /**
     * Main scheduling entry point.
     *
     * <p>The {@code @Async} annotation ensures the actual work is offloaded to the
     * agent thread pool, so the Spring scheduler thread is never blocked by a
     * potentially long LLM call.
     *
     * <p>If no pending run is found the method returns early without any I/O.
     */
    @Scheduled(cron = "${midas.evolution.cron:0 0 * * * *}")
    @Async(AsyncConfig.AGENT_EXECUTOR)
    public void runEvolutionCycle() {
        log.debug("[ContinuousImprovementService] Evolution cycle triggered.");

        Page<MidasRunEntity> page = runRepository
                .findByStatusInAndNeedsRefactoringTrueOrderByUpdatedAtAsc(
                        EVOLUTION_ELIGIBLE_STATUSES, PageRequest.of(0, 1));

        if (page.isEmpty()) {
            log.debug("[ContinuousImprovementService] No runs pending evolution — cycle complete.");
            return;
        }

        processRun(page.getContent().get(0));
    }

    // ── Core processing ───────────────────────────────────────────────────────

    /**
     * Analyzes a single pipeline run and delivers the evolution report.
     *
     * @param run the {@link MidasRunEntity} to analyze
     */
    void processRun(MidasRunEntity run) {
        String runId = run.getId();
        log.info("[ContinuousImprovementService] Processing evolution for run [{}]: {}",
                runId, abbreviate(run.getRawUserIdea(), 80));

        try {
            String codeContext = buildCodeContext(run);

            if (codeContext == null || codeContext.isBlank()) {
                log.warn("[ContinuousImprovementService] No code context available for run [{}] " +
                         "— clearing flag and skipping analysis.", runId);
                persistenceService.clearNeedsRefactoring(runId);
                return;
            }

            String report = evolutionAgent.analyzeCode(codeContext, runId);

            // Persist the report FIRST — ensures the history is saved even if Telegram fails.
            saveEvolutionLog(runId, report);

            // Clear the flag after saving the report to avoid re-processing.
            persistenceService.clearNeedsRefactoring(runId);

            sendTelegramNotification(run, report);

            log.info("[ContinuousImprovementService] Evolution cycle complete for run [{}].", runId);

        } catch (Exception e) {
            // Never re-throw: a failed analysis must not crash the scheduler.
            log.error("[ContinuousImprovementService] Evolution analysis failed for run [{}]: {}",
                    runId, e.getMessage(), e);
        }
    }

    // ── Evolution log persistence ─────────────────────────────────────────────

    /**
     * Persists the EvolutionAgent report to {@code midas_evolution_log}.
     * Wrapped in its own try-catch so a DB failure never aborts the broader cycle.
     *
     * @param runId  pipeline run ID
     * @param report Markdown report produced by {@link EvolutionAgent}
     */
    private void saveEvolutionLog(String runId, String report) {
        try {
            MidasEvolutionLogEntity entry = MidasEvolutionLogEntity.builder()
                    .runId(runId)
                    .refactoringReport(report)
                    .build();
            evolutionLogRepository.save(entry);
            log.debug("[ContinuousImprovementService] Evolution log persisted for run [{}].", runId);
        } catch (Exception e) {
            log.error("[ContinuousImprovementService] Failed to persist evolution log for run [{}]: {}",
                    runId, e.getMessage(), e);
        }
    }

    // ── Code context builder ──────────────────────────────────────────────────

    /**
     * Assembles a structured code-review prompt payload from the agent logs of a
     * completed run.
     *
     * <p>Logs are sorted by the {@link #AGENT_PRIORITY} list so the most review-
     * relevant output (ImplementationEngineer) appears first in the context window.
     * Error logs are excluded — they carry no reviewable code.
     *
     * @param run the pipeline run whose logs to collect
     * @return Markdown-structured context string, or {@code null} if no logs exist
     */
    private String buildCodeContext(MidasRunEntity run) {
        List<MidasAgentLogEntity> logs = logRepository
                .findByRunIdAndIsError(run.getId(), false);

        if (logs.isEmpty()) {
            return null;
        }

        logs = logs.stream()
                .sorted(Comparator.comparingInt(l -> agentPriority(l.getAgentType())))
                .toList();

        StringBuilder sb = new StringBuilder(16_384);
        sb.append("# Code Review Context\n\n");
        sb.append("**Project Idea:** ")
          .append(run.getRawUserIdea())
          .append("\n\n---\n\n");

        for (MidasAgentLogEntity logEntry : logs) {
            sb.append("## Agent: ").append(logEntry.getAgentType()).append("\n\n");
            sb.append("```\n");
            sb.append(logEntry.getRawOutput());
            sb.append("\n```\n\n");
        }

        return sb.toString();
    }

    private int agentPriority(String agentType) {
        int idx = AGENT_PRIORITY.indexOf(agentType);
        return idx == -1 ? AGENT_PRIORITY.size() : idx;
    }

    // ── Telegram notification ─────────────────────────────────────────────────

    /**
     * Delivers the evolution report to the Telegram chat that originated the run.
     *
     * <p>Two-step delivery:
     * <ol>
     *   <li>A short HTML summary message with project name and completion notice.</li>
     *   <li>The full Markdown report: sent inline if {@code ≤ TELEGRAM_MAX_TEXT_LENGTH}
     *       characters, or as an attached {@code .md} document otherwise.</li>
     * </ol>
     *
     * <p>Silently skipped when the bot is unavailable or the run has no
     * {@code chatId} (REST-initiated pipelines).
     *
     * @param run    the analyzed pipeline run
     * @param report Markdown report produced by {@link EvolutionAgent}
     */
    private void sendTelegramNotification(MidasRunEntity run, String report) {
        if (run.getChatId() == null) {
            log.debug("[ContinuousImprovementService] Run [{}] has no chat ID — " +
                      "skipping Telegram notification.", run.getId());
            return;
        }

        TelegramPipelineBot bot = telegramBotProvider.getIfAvailable();
        if (bot == null) {
            log.debug("[ContinuousImprovementService] Telegram bot not active — " +
                      "skipping notification for run [{}].", run.getId());
            return;
        }

        long   chatId      = run.getChatId();
        String ideaPreview = abbreviate(run.getRawUserIdea(), 120);

        bot.sendHtmlMessage(chatId,
                "📊 <b>Эволюционный анализ завершен!</b>\n\n" +
                "Я проанализировал ваш проект:\n" +
                "<i>" + escapeHtml(ideaPreview) + "</i>\n\n" +
                "Найдены пути для оптимизации. Полный отчет прикреплен ниже. ⬇️");

        if (report.length() <= TELEGRAM_MAX_TEXT_LENGTH) {
            bot.sendHtmlMessage(chatId, "<pre>" + escapeHtml(report) + "</pre>");
        } else {
            sendReportAsDocument(bot, chatId, run.getId(), report);
        }
    }

    /**
     * Writes the Markdown report to a temporary {@code .md} file and delivers it
     * to Telegram via {@link TelegramPipelineBot#sendArtifactDocument}.
     * The temp file is always deleted in a {@code finally} block.
     *
     * @param bot    active Telegram bot instance
     * @param chatId destination chat
     * @param runId  pipeline run ID (used in the filename for traceability)
     * @param report full Markdown report content
     */
    private void sendReportAsDocument(TelegramPipelineBot bot,
                                      long chatId,
                                      String runId,
                                      String report) {
        File tempFile = null;
        try {
            tempFile = File.createTempFile("evolution-report-" + runId + "-", ".md");
            Files.writeString(tempFile.toPath(), report, StandardCharsets.UTF_8);
            bot.sendArtifactDocument(chatId, tempFile,
                    "📊 <b>MIDAS Evolution Report</b>\n" +
                    "<i>Полный отчет рефакторинга — run: " + runId + "</i>");

        } catch (IOException e) {
            log.error("[ContinuousImprovementService] Failed to create report document " +
                      "for chat [{}]: {}", chatId, e.getMessage(), e);
            String fallback = report.length() > TELEGRAM_MAX_TEXT_LENGTH
                    ? report.substring(0, TELEGRAM_MAX_TEXT_LENGTH) + "\n\n<i>[отчет обрезан]</i>"
                    : report;
            bot.sendHtmlMessage(chatId, "<pre>" + escapeHtml(fallback) + "</pre>");

        } finally {
            if (tempFile != null && tempFile.exists() && !tempFile.delete()) {
                log.warn("[ContinuousImprovementService] Could not delete temp file [{}].",
                        tempFile.getAbsolutePath());
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String abbreviate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen - 3) + "...";
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}

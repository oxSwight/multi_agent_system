package com.midas.d3.statemachine.action;

import com.midas.d3.context.MidasContext;
import com.midas.d3.config.AsyncConfig;
import com.midas.d3.persistence.PersistenceService;
import com.midas.d3.statemachine.MidasEvent;
import com.midas.d3.statemachine.MidasState;
import com.midas.d3.statemachine.PipelineContextKeys;
import com.midas.d3.telegram.ArtifactPackagingService;
import com.midas.d3.telegram.TelegramPipelineBot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.statemachine.StateContext;
import org.springframework.statemachine.action.Action;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * State-entry {@link Action} that fires when the pipeline transitions into the
 * {@link MidasState#COMPLETED} state.
 *
 * <h2>Responsibility</h2>
 * <ol>
 *   <li>Always persists the final {@code COMPLETED} status to the database.</li>
 *   <li>Checks whether the run was initiated from Telegram (i.e.
 *       {@link MidasContext#getTelegramChatId()} is non-null).</li>
 *   <li>If yes — and if the Telegram bot bean is available — asynchronously packages
 *       all pipeline artifacts into a ZIP via {@link ArtifactPackagingService}, persists
 *       the artifact path, and sends the ZIP to the originating chat via Telegram's
 *       {@code sendDocument} API.</li>
 *   <li>If packaging fails with an {@link IOException}, logs the error and sends a
 *       human-readable error message to Telegram so the user is not left in the dark.</li>
 *   <li>Always deletes the temporary ZIP file in a {@code finally} block.</li>
 * </ol>
 *
 * <h2>Telegram-optional design</h2>
 * {@link TelegramPipelineBot} is injected via Spring's {@link ObjectProvider} so this
 * action is safe to instantiate even when {@code midas.telegram.enabled=false}. When the
 * bot bean is absent or the pipeline was REST-initiated (no {@code telegramChatId}), the
 * Telegram delivery step is skipped but the DB status is still updated.
 *
 * <h2>Async execution</h2>
 * File packaging and network I/O run on the shared {@value AsyncConfig#AGENT_EXECUTOR}
 * thread pool so the State Machine thread is released immediately.
 */
@Slf4j
@Component
public class PipelineCompletionAction implements Action<MidasState, MidasEvent> {

    private static final String CAPTION_SUCCESS =
            "✅ <b>Пайплайн завершен.</b> Итоговые артефакты сгенерированы.";

    private static final String CAPTION_DEGRADED =
            "⚠️ <b>Пайплайн завершён с оговорками.</b> Доставлен частичный артефакт: часть функций "
            + "не удалось реализовать в рамках бюджета генерации, и сборка НЕ проверялась. "
            + "Честный отчёт о покрытии — в <code>MIDAS_COVERAGE_REPORT.md</code>.";

    private final ArtifactPackagingService           packagingService;
    private final ObjectProvider<TelegramPipelineBot> telegramBotProvider;
    private final Executor                           agentTaskExecutor;
    private final PersistenceService                 persistenceService;

    public PipelineCompletionAction(
            ArtifactPackagingService packagingService,
            ObjectProvider<TelegramPipelineBot> telegramBotProvider,
            @Qualifier(AsyncConfig.AGENT_EXECUTOR) Executor agentTaskExecutor,
            PersistenceService persistenceService) {
        this.packagingService    = packagingService;
        this.telegramBotProvider = telegramBotProvider;
        this.agentTaskExecutor   = agentTaskExecutor;
        this.persistenceService  = persistenceService;
    }

    // ── Action ────────────────────────────────────────────────────────────────

    @Override
    public void execute(StateContext<MidasState, MidasEvent> context) {
        MidasContext ctx = (MidasContext) context.getExtendedState()
                .getVariables().get(PipelineContextKeys.MIDAS_CONTEXT);

        if (ctx == null) {
            log.error("[PipelineCompletionAction] MidasContext not found in ExtendedState — " +
                      "cannot deliver artifacts.");
            return;
        }

        String runId = ctx.getPipelineRunId();

        // Idempotency latch: COMPLETED is entered via a choice transition whose action
        // (StoreArtifactAction) already invokes this completion, AND this same action is
        // registered as the COMPLETED stateEntry action — so execute() is called twice on a
        // single completion. Without this guard the artifact ZIP is packaged and delivered twice
        // (the user sees two identical archive messages). Map.put returns the previous value, so a
        // non-null/true previous means delivery was already initiated → no-op. Both invocations run
        // sequentially on the state-machine thread, so the check-and-set needs no extra locking.
        Map<Object, Object> vars = context.getExtendedState().getVariables();
        if (Boolean.TRUE.equals(vars.put(PipelineContextKeys.ARTIFACT_DELIVERY_INITIATED, Boolean.TRUE))) {
            log.debug("[PipelineCompletionAction] Artifact delivery already initiated for run [{}] — "
                    + "skipping duplicate completion.", runId);
            return;
        }

        // Graceful degradation: when DegradeToGapsAction has flagged DEGRADED_COMPLETION the run is
        // finalized as COMPLETED_WITH_GAPS (distinct DB status + caption) but reuses this exact
        // packaging/delivery path — the client always gets an artifact, never a crash.
        boolean degraded = Boolean.TRUE.equals(vars.get(PipelineContextKeys.DEGRADED_COMPLETION));

        if (ctx.getTelegramChatId() == null) {
            // REST-initiated run: package artifacts locally, persist path, skip Telegram delivery.
            final MidasContext restCtx = ctx;
            CompletableFuture.runAsync(() -> packageRestArtifacts(restCtx, degraded), agentTaskExecutor);
            log.debug("[PipelineCompletionAction] Run [{}] REST-initiated — packaging artifacts locally{}.",
                    runId, degraded ? " (degraded)" : "");
            return;
        }

        TelegramPipelineBot bot = telegramBotProvider.getIfAvailable();
        if (bot == null) {
            persistCompletionWithoutArtifact(runId, degraded);
            log.debug("[PipelineCompletionAction] Telegram bot not active — " +
                      "skipping artifact delivery for run [{}].", runId);
            return;
        }

        // Capture finals for lambda — ctx and bot are effectively final at this point
        final MidasContext       finalCtx = ctx;
        final TelegramPipelineBot finalBot = bot;
        CompletableFuture.runAsync(() -> deliverArtifacts(finalCtx, finalBot, degraded), agentTaskExecutor);
    }

    // ── Async delivery ────────────────────────────────────────────────────────

    private void packageRestArtifacts(MidasContext ctx, boolean degraded) {
        String runId = ctx.getPipelineRunId();
        File zipFile = null;
        try {
            log.info("[PipelineCompletionAction] Packaging REST artifacts for run [{}]{}.",
                    runId, degraded ? " (degraded)" : "");
            zipFile = packagingService.packageResults(ctx);
            persistCompletion(runId, zipFile.getAbsolutePath(), degraded);
            log.info("[PipelineCompletionAction] REST artifact ZIP saved at [{}] for run [{}].",
                    zipFile.getAbsolutePath(), runId);
        } catch (Exception e) {
            persistCompletionWithoutArtifact(runId, degraded);
            log.error("[PipelineCompletionAction] Failed to package REST artifacts for run [{}]: {}",
                    runId, e.getMessage(), e);
        } finally {
            // Keep the ZIP on disk for REST consumers — do not delete after packaging.
            if (zipFile == null) {
                log.warn("[PipelineCompletionAction] No ZIP produced for REST run [{}].", runId);
            }
        }
    }

    private void deliverArtifacts(MidasContext ctx, TelegramPipelineBot bot, boolean degraded) {
        long   chatId  = ctx.getTelegramChatId();
        String runId   = ctx.getPipelineRunId();
        File   zipFile = null;

        try {
            log.info("[PipelineCompletionAction] Packaging artifacts for run [{}], chat [{}]{}.",
                    runId, chatId, degraded ? " (degraded)" : "");

            zipFile = packagingService.packageResults(ctx);

            // Persist the artifact path and mark COMPLETED (or COMPLETED_WITH_GAPS) before sending.
            persistCompletion(runId, zipFile.getAbsolutePath(), degraded);

            boolean delivered = bot.sendArtifactDocument(chatId, zipFile,
                    degraded ? CAPTION_DEGRADED : CAPTION_SUCCESS);
            if (delivered) {
                bot.updatePipelineCompletionMessage(ctx, true);
            }

            log.info("[PipelineCompletionAction] Artifact ZIP delivered to chat [{}] for run [{}].",
                    chatId, runId);

        } catch (IOException e) {
            // ZIP creation failed — still mark run as completed without artifact.
            persistCompletionWithoutArtifact(runId, degraded);
            log.error("[PipelineCompletionAction] Failed to package artifacts for run [{}]: {}",
                    runId, e.getMessage(), e);
            String completionPhrase = degraded
                    ? "Пайплайн завершён с оговорками"
                    : "Пайплайн успешно завершен";
            bot.sendHtmlMessage(chatId,
                    "⚠️ <b>" + completionPhrase + "</b>, однако при создании архива " +
                    "артефактов произошла ошибка:\n<code>" + escapeHtml(e.getMessage()) + "</code>\n\n" +
                    "Вы можете получить результаты через REST API:\n" +
                    "<code>GET /api/v1/pipelines/" + runId + "/context</code>");

        } catch (Exception e) {
            persistCompletionWithoutArtifact(runId, degraded);
            log.error("[PipelineCompletionAction] Unexpected error during artifact delivery " +
                      "for run [{}]: {}", runId, e.getMessage(), e);

        } finally {
            deleteZipSilently(zipFile, runId);
        }
    }

    // ── Completion status (clean vs. graceful degradation) ──────────────────────

    /** Marks the run COMPLETED_WITH_GAPS when degraded, otherwise a clean COMPLETED — with artifact. */
    private void persistCompletion(String runId, String artifactPath, boolean degraded) {
        if (degraded) {
            persistenceService.completeRunWithGaps(runId, artifactPath);
        } else {
            persistenceService.completeRun(runId, artifactPath);
        }
    }

    /** Same terminal status selection, for the no-artifact path (packaging failed or bot absent). */
    private void persistCompletionWithoutArtifact(String runId, boolean degraded) {
        if (degraded) {
            persistenceService.completeRunWithGapsWithoutArtifact(runId);
        } else {
            persistenceService.completeRunWithoutArtifact(runId);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void deleteZipSilently(File zipFile, String runId) {
        if (zipFile == null) return;
        if (zipFile.exists() && !zipFile.delete()) {
            log.warn("[PipelineCompletionAction] Could not delete temp ZIP [{}] for run [{}].",
                    zipFile.getAbsolutePath(), runId);
        }
    }

    private static String escapeHtml(String s) {
        if (s == null) return "unknown error";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}

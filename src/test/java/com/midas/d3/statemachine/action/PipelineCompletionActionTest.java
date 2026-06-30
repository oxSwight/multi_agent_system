package com.midas.d3.statemachine.action;

import com.midas.d3.context.MidasContext;
import com.midas.d3.persistence.PersistenceService;
import com.midas.d3.statemachine.MidasEvent;
import com.midas.d3.statemachine.MidasState;
import com.midas.d3.statemachine.PipelineContextKeys;
import com.midas.d3.telegram.ArtifactPackagingService;
import com.midas.d3.telegram.TelegramPipelineBot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.statemachine.ExtendedState;
import org.springframework.statemachine.StateContext;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link PipelineCompletionAction}.
 *
 * <p>Uses a synchronous in-line {@link Executor} so async code runs on the test thread,
 * making assertions deterministic without thread sleeps.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PipelineCompletionAction Tests")
class PipelineCompletionActionTest {

    @Mock private ArtifactPackagingService             packagingService;
    @Mock private ObjectProvider<TelegramPipelineBot>  telegramBotProvider;
    @Mock private TelegramPipelineBot                  telegramBot;
    @Mock private StateContext<MidasState, MidasEvent> stateContext;
    @Mock private ExtendedState                        extendedState;
    @Mock private PersistenceService                   persistenceService;

    /** Synchronous executor — runs submitted tasks on the calling thread for deterministic tests. */
    private static final Executor SYNC_EXECUTOR = Runnable::run;

    private PipelineCompletionAction action;

    @BeforeEach
    void setUp() {
        action = new PipelineCompletionAction(packagingService, telegramBotProvider, SYNC_EXECUTOR, persistenceService);
        when(stateContext.getExtendedState()).thenReturn(extendedState);
    }

    // ── Guard: no context ─────────────────────────────────────────────────────

    @Test
    @DisplayName("Null MidasContext in ExtendedState — action is no-op, no service calls")
    void execute_nullContext_noInteractions() {
        Map<Object, Object> vars = new HashMap<>();
        when(extendedState.getVariables()).thenReturn(vars);

        action.execute(stateContext);

        verifyNoInteractions(packagingService, telegramBotProvider);
    }

    // ── Guard: REST-initiated (no chatId) ─────────────────────────────────────

    @Test
    @DisplayName("MidasContext without telegramChatId (REST-initiated) — packages locally, skips Telegram")
    void execute_noTelegramChatId_packagesLocallySkipsTelegram() throws IOException {
        MidasContext ctx = MidasContext.start("REST idea", "run-rest-001");
        stubContextVars(ctx);

        File fakeZip = File.createTempFile("rest_artifact", ".zip");
        when(packagingService.packageResults(ctx)).thenReturn(fakeZip);

        action.execute(stateContext);

        // REST-initiated runs package artifacts locally and persist the path,
        // but must NOT touch Telegram delivery.
        verify(packagingService).packageResults(ctx);
        verify(persistenceService).completeRun(eq("run-rest-001"), anyString());
        verifyNoInteractions(telegramBotProvider);

        fakeZip.delete();
    }

    // ── Guard: telegram bot unavailable ──────────────────────────────────────

    @Test
    @DisplayName("Telegram bot bean absent (disabled) — skips delivery even if chatId is set")
    void execute_botUnavailable_skipsDelivery() {
        MidasContext ctx = MidasContext.start("Bot idea", "run-nobot-001")
                .withTelegramChatId(99999L);
        stubContextVars(ctx);
        when(telegramBotProvider.getIfAvailable()).thenReturn(null);

        action.execute(stateContext);

        verifyNoInteractions(packagingService);
    }

    // ── Happy path: successful delivery ──────────────────────────────────────

    @Test
    @DisplayName("Happy path: packages artifacts and sends document to Telegram")
    void execute_happyPath_packagesAndSendsDocument() throws IOException {
        long chatId = 123456789L;
        MidasContext ctx = MidasContext.start("Great idea", "run-happy-001")
                .withTelegramChatId(chatId);
        stubContextVars(ctx);
        when(telegramBotProvider.getIfAvailable()).thenReturn(telegramBot);

        File fakeZip = File.createTempFile("test_artifact", ".zip");
        when(packagingService.packageResults(ctx)).thenReturn(fakeZip);
        when(telegramBot.sendArtifactDocument(eq(chatId), eq(fakeZip), contains("✅"))).thenReturn(true);

        action.execute(stateContext);

        verify(packagingService).packageResults(ctx);
        verify(telegramBot).sendArtifactDocument(eq(chatId), eq(fakeZip), contains("✅"));
        verify(telegramBot).updatePipelineCompletionMessage(eq(ctx), eq(true));
        assertThat(fakeZip).doesNotExist();
    }

    // ── Idempotency: double invocation delivers exactly once ─────────────────

    @Test
    @DisplayName("Idempotent: two execute() calls (choice action + COMPLETED entry) package and send once")
    void execute_calledTwice_deliversExactlyOnce() throws IOException {
        long chatId = 555000111L;
        MidasContext ctx = MidasContext.start("Idempotent idea", "run-idem-001")
                .withTelegramChatId(chatId);
        stubContextVars(ctx);
        when(telegramBotProvider.getIfAvailable()).thenReturn(telegramBot);

        File fakeZip = File.createTempFile("idem_artifact", ".zip");
        when(packagingService.packageResults(ctx)).thenReturn(fakeZip);
        when(telegramBot.sendArtifactDocument(eq(chatId), any(), anyString())).thenReturn(true);

        // Mirrors production: StoreArtifactAction invokes completion, then the COMPLETED
        // stateEntry action invokes it again on the same ExtendedState.
        action.execute(stateContext);
        action.execute(stateContext);

        verify(packagingService, times(1)).packageResults(ctx);
        verify(telegramBot, times(1)).sendArtifactDocument(eq(chatId), any(), anyString());

        fakeZip.delete();
    }

    // ── Error path: packaging IOException ────────────────────────────────────

    @Test
    @DisplayName("IOException during packaging → error message sent to Telegram, no document sent")
    void execute_packagingIOException_sendsErrorMessage() throws IOException {
        long chatId = 111222333L;
        MidasContext ctx = MidasContext.start("Error idea", "run-err-001")
                .withTelegramChatId(chatId);
        stubContextVars(ctx);
        when(telegramBotProvider.getIfAvailable()).thenReturn(telegramBot);
        when(packagingService.packageResults(any())).thenThrow(new IOException("Disk full"));

        action.execute(stateContext);

        verify(telegramBot, never()).sendArtifactDocument(anyLong(), any(), anyString());
        verify(telegramBot, never()).updatePipelineCompletionMessage(any(), anyBoolean());
        verify(telegramBot).sendHtmlMessage(eq(chatId), contains("Disk full"));
    }

    @Test
    @DisplayName("IOException message is HTML-escaped in the error reply")
    void execute_ioExceptionMessage_isHtmlEscaped() throws IOException {
        long chatId = 444555666L;
        MidasContext ctx = MidasContext.start("XSS idea", "run-xss-001")
                .withTelegramChatId(chatId);
        stubContextVars(ctx);
        when(telegramBotProvider.getIfAvailable()).thenReturn(telegramBot);
        when(packagingService.packageResults(any()))
                .thenThrow(new IOException("<script>alert('xss')</script>"));

        action.execute(stateContext);

        verify(telegramBot).sendHtmlMessage(eq(chatId),
                argThat(msg -> msg.contains("&lt;script&gt;") && !msg.contains("<script>")));
    }

    // ── Cleanup: ZIP deleted even on success ──────────────────────────────────

    @Test
    @DisplayName("Temporary ZIP is deleted in finally block after successful send")
    void execute_zipIsDeletedAfterSuccessfulSend() throws IOException {
        long chatId = 777888999L;
        MidasContext ctx = MidasContext.start("Cleanup idea", "run-cleanup-001")
                .withTelegramChatId(chatId);
        stubContextVars(ctx);
        when(telegramBotProvider.getIfAvailable()).thenReturn(telegramBot);

        File tempZip = File.createTempFile("cleanup_test", ".zip");
        when(packagingService.packageResults(ctx)).thenReturn(tempZip);

        action.execute(stateContext);

        assertThat(tempZip).as("Temp ZIP should be deleted after send").doesNotExist();
    }

    @Test
    @DisplayName("Temporary ZIP is deleted even when sendArtifactDocument throws")
    void execute_zipIsDeletedEvenWhenSendFails() throws IOException {
        long chatId = 101010L;
        MidasContext ctx = MidasContext.start("Send fail idea", "run-sendfail-001")
                .withTelegramChatId(chatId);
        stubContextVars(ctx);
        when(telegramBotProvider.getIfAvailable()).thenReturn(telegramBot);

        File tempZip = File.createTempFile("sendfail_test", ".zip");
        when(packagingService.packageResults(ctx)).thenReturn(tempZip);
        doReturn(false).when(telegramBot).sendArtifactDocument(anyLong(), any(), anyString());

        action.execute(stateContext);

        verify(telegramBot, never()).updatePipelineCompletionMessage(any(), anyBoolean());
        assertThat(tempZip).as("Temp ZIP should be deleted even after send failure").doesNotExist();
    }

    // ── Whitelist behaviour (via TelegramBotProperties binding — integration) ─

    @Test
    @DisplayName("Correct chatId passed through from MidasContext to sendArtifactDocument")
    void execute_correctChatIdPassedToBot() throws IOException {
        long expectedChatId = 999888777L;
        MidasContext ctx = MidasContext.start("Chat ID test", "run-chatid-001")
                .withTelegramChatId(expectedChatId);
        stubContextVars(ctx);
        when(telegramBotProvider.getIfAvailable()).thenReturn(telegramBot);

        File fakeZip = File.createTempFile("chatid_test", ".zip");
        when(packagingService.packageResults(ctx)).thenReturn(fakeZip);

        action.execute(stateContext);

        verify(telegramBot).sendArtifactDocument(eq(expectedChatId), any(File.class), anyString());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void stubContextVars(MidasContext ctx) {
        Map<Object, Object> vars = new HashMap<>();
        vars.put(PipelineContextKeys.MIDAS_CONTEXT, ctx);
        when(extendedState.getVariables()).thenReturn(vars);
    }

    private static org.assertj.core.api.AbstractFileAssert<?> assertThat(File file) {
        return org.assertj.core.api.Assertions.assertThat(file);
    }
}

package com.midas.d3.telegram;

import com.midas.d3.context.MidasContext;
import com.midas.d3.security.JwtService;
import com.midas.d3.statemachine.HumanInTheLoopRegistry;
import com.midas.d3.statemachine.PipelineOrchestrator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.io.File;
import java.util.Optional;
import java.util.Set;

/**
 * Telegram bot frontend for the MIDAS_D3 pipeline.
 *
 * <h2>User flow</h2>
 * <ol>
 *   <li>User sends any text message describing the software idea.</li>
 *   <li>Bot immediately replies with <em>"🔄 Инициализация..."</em> and records the
 *       {@code messageId} of that reply.</li>
 *   <li>Bot starts the pipeline in <em>auto-drive</em> mode via
 *       {@link PipelineOrchestrator#startPipelineWithListener}, passing a
 *       {@link TelegramStateListener} tied to the reply message.</li>
 *   <li>As the pipeline advances through its 7 stages, the listener calls
 *       {@code EditMessageText} to update the progress bar in-place.</li>
 *   <li>On completion or error the final message is rendered and no further edits occur.</li>
 * </ol>
 *
 * <h2>Command support</h2>
 * {@code /start} and {@code /help} print a usage summary. Any other text is treated as a
 * pipeline idea and triggers the flow above.
 *
 * <h2>Activation</h2>
 * The bean (and its config sibling {@link TelegramBotConfig}) are only created when
 * {@code midas.telegram.enabled=true}. Without that flag the bot does not start
 * and the rest of the application functions normally via REST.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "midas.telegram", name = "enabled", havingValue = "true")
public class TelegramPipelineBot extends TelegramLongPollingBot {

    private static final String INIT_MESSAGE =
            "🔄 <b>MIDAS Pipeline запущен!</b>\n[⬜⬜⬜⬜⬜⬜] <i>Инициализация...</i>";

    private static final String HELP_TEXT =
            """
            <b>MIDAS D3 — Pipeline разработки ПО</b>
            
            Отправь мне описание программного продукта, и я запущу полный цикл разработки:
            
            1️⃣ Системный Анализ
            2️⃣ Архитектурное Проектирование
            3️⃣ Стратегия Интеграции
            4️⃣ Генерация кода
            5️⃣ Генерация тестов (QA)
            6️⃣ SecOps аудит
            7️⃣ Контроль качества
            
            Просто опиши свою идею и нажми отправить!
            """;

    private static final String DASHBOARD_BASE_URL = "http://localhost:3000/auth";

    private final TelegramBotProperties  properties;
    private final PipelineOrchestrator   pipelineOrchestrator;
    private final HumanInTheLoopRegistry humanInTheLoopRegistry;
    private final JwtService             jwtService;

    public TelegramPipelineBot(TelegramBotProperties properties,
                               PipelineOrchestrator pipelineOrchestrator,
                               HumanInTheLoopRegistry humanInTheLoopRegistry,
                               JwtService jwtService) {
        super(properties.getBotToken());
        this.properties             = properties;
        this.pipelineOrchestrator   = pipelineOrchestrator;
        this.humanInTheLoopRegistry = humanInTheLoopRegistry;
        this.jwtService             = jwtService;
    }

    // ── TelegramLongPollingBot ────────────────────────────────────────────────

    @Override
    public String getBotUsername() {
        return properties.getBotUsername();
    }

    @Override
    public void onUpdateReceived(Update update) {
        if (update == null) return;
        if (!update.hasMessage() || !update.getMessage().hasText()) return;

        Message msg    = update.getMessage();
        long    chatId = msg.getChatId();
        String  text   = msg.getText().trim();

        log.debug("[TelegramPipelineBot] Received from chat [{}]: {}", chatId,
                text.length() > 80 ? text.substring(0, 80) + "…" : text);

        // ── Whitelist guard ───────────────────────────────────────────────────
        Set<Long> allowedIds = properties.getAllowedChatIds();
        if (!allowedIds.isEmpty() && !allowedIds.contains(chatId)) {
            log.warn("[TelegramPipelineBot] Access denied for chat [{}] — not in whitelist.", chatId);
            sendHtml(chatId, "⛔️ <b>Доступ запрещен.</b>\nВы не авторизованы для использования данного бота.");
            return;
        }

        if (text.startsWith("/start") || text.startsWith("/help")) {
            sendHtml(chatId, HELP_TEXT);
            return;
        }

        if (text.startsWith("/dashboard")) {
            handleDashboardCommand(chatId);
            return;
        }

        // ── Human-in-the-Loop: handle Reply to bot's clarification question ──
        if (isReplyToBotMessage(msg)) {
            Optional<String> waitingRunId = humanInTheLoopRegistry.resolve(chatId);
            if (waitingRunId.isPresent()) {
                handleUserReply(chatId, waitingRunId.get(), text);
                return;
            }
            // Reply to a non-waiting message — fall through to start a new pipeline
        }

        startPipelineForUser(chatId, text);
    }

    // ── Human-in-the-Loop reply handling ─────────────────────────────────────

    /**
     * Returns {@code true} if the given message is a Telegram Reply (i.e. it quotes
     * another message). Used to detect that the user is responding to the bot's
     * clarifying-questions prompt.
     */
    private boolean isReplyToBotMessage(Message msg) {
        return msg.isReply() && msg.getReplyToMessage() != null;
    }

    /**
     * Processes the user's clarification reply: delivers it to the state machine via
     * {@link PipelineOrchestrator#userReplied} and clears the waiting registration.
     */
    private void handleUserReply(long chatId, String runId, String replyText) {
        log.info("[TelegramPipelineBot] Received reply for waiting run [{}] from chat [{}].", runId, chatId);
        try {
            humanInTheLoopRegistry.clear(chatId);
            pipelineOrchestrator.userReplied(runId, replyText);
            sendHtml(chatId,
                    "✅ <b>Ответ получен!</b> Системный Аналитик продолжает генерацию ТЗ...");
        } catch (Exception e) {
            log.error("[TelegramPipelineBot] Failed to resume run [{}] after user reply: {}",
                    runId, e.getMessage(), e);
            sendHtml(chatId,
                    "❌ <b>Не удалось возобновить пайплайн.</b>\nПричина: <code>"
                    + escapeHtml(e.getMessage()) + "</code>");
        }
    }

    // ── Magic Link ────────────────────────────────────────────────────────────

    /**
     * Generates a 24-hour JWT Magic Link for the MIDAS dashboard and sends it
     * to the requesting chat. Only whitelisted chats may receive a link.
     *
     * @param chatId requesting Telegram chat ID
     */
    private void handleDashboardCommand(long chatId) {
        Set<Long> allowedIds = properties.getAllowedChatIds();
        if (!allowedIds.isEmpty() && !allowedIds.contains(chatId)) {
            log.warn("[TelegramPipelineBot] Dashboard access denied for chat [{}].", chatId);
            sendHtml(chatId, "⛔️ <b>Доступ запрещен.</b>\nВы не авторизованы для использования дашборда.");
            return;
        }

        try {
            String token   = jwtService.generateToken(chatId);
            String magicUrl = DASHBOARD_BASE_URL + "?token=" + token;

            sendHtml(chatId,
                    "📊 <b>Ваш доступ к дашборду (24ч):</b>\n" +
                    "<a href=\"" + magicUrl + "\">" + magicUrl + "</a>\n\n" +
                    "<i>⚠️ Ссылка действительна 24 часа. Не передавайте её третьим лицам.</i>");

            log.info("[TelegramPipelineBot] Magic link issued for chat [{}].", chatId);

        } catch (Exception e) {
            log.error("[TelegramPipelineBot] Failed to generate magic link for chat [{}]: {}",
                    chatId, e.getMessage(), e);
            sendHtml(chatId, "❌ <b>Не удалось сгенерировать ссылку.</b>\nПопробуйте позже.");
        }
    }

    // ── Pipeline launch ───────────────────────────────────────────────────────

    private void startPipelineForUser(long chatId, String rawIdea) {
        // 1. Send the initial "Initializing..." message and capture its messageId
        Integer initMsgId = sendInitMessage(chatId);
        if (initMsgId == null) {
            log.error("[TelegramPipelineBot] Could not send init message to chat [{}].", chatId);
            return;
        }

        // 2. Create a per-run listener tied to that message
        TelegramStateListener listener = new TelegramStateListener(this, chatId, initMsgId);

        // 3. Start the pipeline in auto-drive mode
        try {
            String runId = pipelineOrchestrator.startPipelineWithListener(
                    rawIdea, chatId, initMsgId, listener);
            log.info("[TelegramPipelineBot] Pipeline [{}] started for chat [{}].", runId, chatId);

        } catch (Exception e) {
            log.error("[TelegramPipelineBot] Failed to start pipeline for chat [{}]: {}",
                    chatId, e.getMessage(), e);
            editMessage(chatId, initMsgId,
                    "❌ <b>Не удалось запустить пайплайн</b>\n\n" +
                    "Причина: <code>" + escapeHtml(e.getMessage()) + "</code>");
        }
    }

    // ── Telegram helpers ──────────────────────────────────────────────────────

    /**
     * Sends the initial status message and returns its {@code messageId}, or
     * {@code null} if the Telegram API call failed.
     */
    private Integer sendInitMessage(long chatId) {
        try {
            Message sent = execute(SendMessage.builder()
                    .chatId(String.valueOf(chatId))
                    .text(INIT_MESSAGE)
                    .parseMode("HTML")
                    .disableWebPagePreview(true)
                    .build());
            return sent.getMessageId();
        } catch (TelegramApiException e) {
            log.error("[TelegramPipelineBot] Failed to send init message to chat [{}]: {}",
                    chatId, e.getMessage());
            return null;
        }
    }

    void editMessage(long chatId, int messageId, String html) {
        try {
            execute(EditMessageText.builder()
                    .chatId(String.valueOf(chatId))
                    .messageId(messageId)
                    .text(html)
                    .parseMode("HTML")
                    .disableWebPagePreview(true)
                    .build());
        } catch (TelegramApiException e) {
            log.warn("[TelegramPipelineBot] Could not edit message [{}] in chat [{}]: {}",
                    messageId, chatId, e.getMessage());
        }
    }

    /**
     * Sends a ZIP (or any file) artifact to the specified chat with an HTML caption.
     * Called by {@link com.midas.d3.statemachine.action.PipelineCompletionAction} after
     * the pipeline successfully completes.
     *
     * @param chatId  Telegram chat ID of the recipient
     * @param file    artifact file to send (usually a ZIP archive)
     * @param caption HTML-formatted caption displayed below the document
     */
    public boolean sendArtifactDocument(long chatId, File file, String caption) {
        try {
            execute(SendDocument.builder()
                    .chatId(String.valueOf(chatId))
                    .document(new InputFile(file, file.getName()))
                    .caption(caption)
                    .parseMode("HTML")
                    .build());
            log.info("[TelegramPipelineBot] Artifact document [{}] sent to chat [{}].",
                    file.getName(), chatId);
            return true;
        } catch (TelegramApiException e) {
            log.error("[TelegramPipelineBot] Failed to send document to chat [{}]: {}",
                    chatId, e.getMessage(), e);
            return false;
        }
    }

    public void updatePipelineCompletionMessage(MidasContext ctx, boolean documentDelivered) {
        if (ctx.getTelegramChatId() == null || ctx.getTelegramMessageId() == null) {
            return;
        }
        editMessage(ctx.getTelegramChatId(), ctx.getTelegramMessageId(),
                TelegramStateListener.renderFinalCompletion(ctx, documentDelivered));
    }

    /**
     * Pushes an honest terminal-failure notice to the originating chat on the durable-ERROR fallback
     * path ({@link com.midas.d3.statemachine.AgentDispatcher}), where the machine never entered the
     * ERROR state — so {@link TelegramStateListener} never rendered the failure and the user's
     * in-progress message would otherwise stay stale forever. Edits that message in place, mirroring
     * what the state listener would have shown on a normal ERROR entry. No-op for REST-initiated runs
     * (no chat / message id).
     *
     * @param ctx    run context carrying the originating {@code telegramChatId} / {@code telegramMessageId}
     * @param reason honest failure reason (the agent error) — the machine never wrote it into the context
     */
    public void updatePipelineErrorMessage(MidasContext ctx, String reason) {
        if (ctx.getTelegramChatId() == null || ctx.getTelegramMessageId() == null) {
            return;
        }
        editMessage(ctx.getTelegramChatId(), ctx.getTelegramMessageId(),
                TelegramStateListener.renderErrorWithReason(ctx, reason));
    }

    /**
     * Sends a plain HTML text message to the specified chat.
     * Exposed publicly so that {@link com.midas.d3.statemachine.action.PipelineCompletionAction}
     * can notify the user of packaging errors.
     *
     * @param chatId Telegram chat ID
     * @param html   HTML-formatted message body
     */
    public void sendHtmlMessage(long chatId, String html) {
        sendHtml(chatId, html);
    }

    private void sendHtml(long chatId, String html) {
        try {
            execute(SendMessage.builder()
                    .chatId(String.valueOf(chatId))
                    .text(html)
                    .parseMode("HTML")
                    .disableWebPagePreview(true)
                    .build());
        } catch (TelegramApiException e) {
            log.warn("[TelegramPipelineBot] Could not send message to chat [{}]: {}", chatId, e.getMessage());
        }
    }

    private String escapeHtml(String s) {
        if (s == null) return "unknown error";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}

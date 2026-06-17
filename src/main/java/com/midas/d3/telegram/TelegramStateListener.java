package com.midas.d3.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import com.midas.d3.context.MidasContext;
import com.midas.d3.statemachine.MidasEvent;
import com.midas.d3.statemachine.MidasState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.statemachine.listener.StateMachineListenerAdapter;
import org.springframework.statemachine.state.State;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.bots.AbsSender;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

/**
 * Per-run {@link org.springframework.statemachine.listener.StateMachineListener} that
 * updates a single Telegram message with a visual progress bar as the pipeline advances.
 *
 * <h2>Progress bar format (7 segments = 7 agents)</h2>
 * <pre>
 *   [🟩⬜⬜⬜⬜⬜⬜] Агент 1: Системный Аналитик работает...
 *   [🟩🟩⬜⬜⬜⬜⬜] Агент 2: Проектирование Архитектуры...
 *   [🟩🟩🟩🟩🟩🟩🟩] ✅ Пайплайн успешно завершен!
 *   [❌ ERROR] Сбой на этапе ...
 * </pre>
 *
 * <h2>Telegram API failure resilience</h2>
 * {@link TelegramApiException}s are caught and logged; they never propagate to the
 * state machine. "Message not modified" errors (same text sent twice) are silently ignored.
 *
 * <p>Instances are created per pipeline run by {@link TelegramPipelineBot} and attached
 * via {@link com.midas.d3.statemachine.PipelineOrchestrator#startPipelineWithListener}.
 */
@Slf4j
public class TelegramStateListener extends StateMachineListenerAdapter<MidasState, MidasEvent> {

    private static final String NOT_MODIFIED_ERROR = "Bad Request: message is not modified";
    private static final String HEADER = "<b>MIDAS Pipeline</b>\n";

    private final AbsSender botSender;
    private final long chatId;
    private final int messageId;

    public TelegramStateListener(AbsSender botSender, long chatId, int messageId) {
        this.botSender  = botSender;
        this.chatId     = chatId;
        this.messageId  = messageId;
    }

    // ── StateMachineListenerAdapter ───────────────────────────────────────────

    @Override
    public void stateChanged(State<MidasState, MidasEvent> from,
                             State<MidasState, MidasEvent> to) {
        if (to == null || to.getId() == null) return;
        MidasState state = to.getId();

        // Skip internal CHOICE pseudo-states — they are not meaningful to the user
        if (state.name().endsWith("_CHOICE")) return;

        String text = renderProgress(state);
        if (text == null) return;

        editMessage(text);
    }

    // ── Progress rendering ────────────────────────────────────────────────────

    private String renderProgress(MidasState state) {
        return switch (state) {
            case SYSTEM_ANALYSIS ->
                HEADER + "[🟩⬜⬜⬜⬜⬜⬜] <i>Агент 1 — Системный Аналитик работает...</i>";

            case WAITING_FOR_USER_INPUT ->
                HEADER + "[⏸️⬜⬜⬜⬜⬜⬜] <b>Пауза — требуется уточнение.</b>\n\n" +
                "🤔 Системный Аналитик задал вопросы. Ответьте <b>Reply</b> на сообщение с вопросами, " +
                "чтобы продолжить генерацию.";

            case ARCHITECTURE_DESIGN ->
                HEADER + "[🟩🟩⬜⬜⬜⬜⬜] <i>Агент 2 — Проектирование Архитектуры...</i>";

            case INTEGRATION_STRATEGY ->
                HEADER + "[🟩🟩🟩⬜⬜⬜⬜] <i>Агент 3 — Стратегия Интеграции...</i>";

            case CODE_GENERATION ->
                HEADER + "[🟩🟩🟩🟩⬜⬜⬜] <i>Агент 4 — Генерация исходного кода...</i>";

            case TEST_GENERATION ->
                HEADER + "[🟩🟩🟩🟩🟩⬜⬜] <i>Агент 5 — Генерация Тестов (QA)...</i>";

            case SECOPS_AUDIT ->
                HEADER + "[🟩🟩🟩🟩🟩🟩⬜] <i>Агент 6 — SecOps Аудит безопасности...</i>";

            case PRODUCT_REVIEW ->
                HEADER + "[🟩🟩🟩🟩🟩🟩🟩] <i>Агент 7 — Контроль качества (Product Owner)...</i>";

            case COMPLETED ->
                HEADER + "[🟩🟩🟩🟩🟩🟩🟩]\n\n" +
                "✅ <b>Пайплайн успешно завершен!</b>\n" +
                "📦 Формирование и отправка архива артефактов...";

            case ERROR ->
                HEADER + "[❌ ОШИБКА]\n\n" +
                "Пайплайн завершился с ошибкой.\n" +
                "Используйте <code>GET /api/v1/pipelines/{runId}/context</code> для деталей.";

            default -> null;
        };
    }

    public static String renderFinalCompletion(MidasContext ctx, boolean documentDelivered) {
        StringBuilder sb = new StringBuilder();
        sb.append(HEADER).append("[🟩🟩🟩🟩🟩🟩🟩]\n\n");
        sb.append("✅ <b>Пайплайн успешно завершен!</b>\n");
        sb.append("Все 7 артефактов сгенерированы и валидированы.\n");
        if (documentDelivered) {
            sb.append("📦 <b>Архив артефактов доставлен в чат.</b>\n");
        }
        String verdict = extractProductReviewVerdict(ctx);
        if (verdict != null) {
            sb.append("Контроль качества: <b>").append(verdict).append("</b>\n");
        }
        sb.append("\nREST: <code>GET /api/v1/pipelines/")
                .append(ctx.getPipelineRunId())
                .append("/artifacts</code>");
        return sb.toString();
    }

    static String extractProductReviewVerdict(MidasContext ctx) {
        JsonNode report = ctx.getProductReviewReport();
        if (report == null) {
            return null;
        }
        JsonNode verdictNode = report.get("verdict");
        if (verdictNode == null || !verdictNode.isTextual()) {
            return null;
        }
        String verdict = verdictNode.asText().strip();
        if ("PASS".equals(verdict) || "PASS_WITH_NOTES".equals(verdict)) {
            return verdict;
        }
        return null;
    }

    // ── Telegram API call ─────────────────────────────────────────────────────

    private void editMessage(String text) {
        try {
            EditMessageText edit = EditMessageText.builder()
                    .chatId(String.valueOf(chatId))
                    .messageId(messageId)
                    .text(text)
                    .parseMode("HTML")
                    .disableWebPagePreview(true)
                    .build();
            botSender.execute(edit);

        } catch (TelegramApiRequestException e) {
            // "message is not modified" means we tried to set the same text — harmless
            if (!NOT_MODIFIED_ERROR.equals(e.getApiResponse())) {
                log.warn("[TelegramStateListener] API error updating message [{}] in chat [{}]: {}",
                        messageId, chatId, e.getApiResponse());
            }
        } catch (TelegramApiException e) {
            // Network timeout or other transient failure — log and continue
            log.warn("[TelegramStateListener] Failed to edit Telegram message [{}]: {}",
                    messageId, e.getMessage());
        } catch (Exception e) {
            log.error("[TelegramStateListener] Unexpected error editing message [{}]: {}",
                    messageId, e.getMessage(), e);
        }
    }
}

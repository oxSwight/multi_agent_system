package com.midas.d3.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import com.midas.d3.context.AuditEntry;
import com.midas.d3.context.MidasContext;
import com.midas.d3.statemachine.MidasEvent;
import com.midas.d3.statemachine.MidasState;
import com.midas.d3.statemachine.PipelineContextKeys;
import com.midas.d3.statemachine.PipelineTopology;
import lombok.extern.slf4j.Slf4j;
import org.springframework.statemachine.StateContext;
import org.springframework.statemachine.listener.StateMachineListenerAdapter;
import org.springframework.statemachine.state.State;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.bots.AbsSender;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

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

    @Override
    public void stateContext(StateContext<MidasState, MidasEvent> stateContext) {
        if (stateContext.getStage() != StateContext.Stage.STATE_CHANGED) {
            return;
        }

        State<MidasState, MidasEvent> target = stateContext.getTarget();
        if (target == null || target.getId() == null) {
            return;
        }
        MidasState state = target.getId();
        if (state.name().endsWith("_CHOICE")) {
            return;
        }

        MidasContext ctx = (MidasContext) stateContext.getExtendedState()
                .getVariables().get(PipelineContextKeys.MIDAS_CONTEXT);

        String text = renderProgress(state, ctx);
        if (text == null) {
            return;
        }

        editMessage(text);
    }

    static String renderProgress(MidasState state, MidasContext ctx) {
        if (state == MidasState.CODE_GENERATION && isRemediationPass(ctx)) {
            return renderRemediationInProgress(ctx);
        }

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
                renderError(ctx);

            default -> null;
        };
    }

    static boolean shouldRenderForStage(StateContext.Stage stage) {
        return stage == StateContext.Stage.STATE_CHANGED;
    }

    static String renderError(MidasContext ctx) {
        String reason = extractPipelineErrorReason(ctx);
        StringBuilder sb = new StringBuilder();
        sb.append(HEADER).append("[❌ ОШИБКА]\n\n");
        sb.append("Пайплайн завершился с ошибкой.\n");
        if (reason != null) {
            sb.append("\nПричина: <b>").append(escapeHtml(reason)).append("</b>\n");
        }
        if (ctx != null && ctx.getPipelineRunId() != null) {
            sb.append("\nREST: <code>GET /api/v1/pipelines/")
                    .append(ctx.getPipelineRunId())
                    .append("/context</code>");
        }
        return sb.toString();
    }

    static String extractPipelineErrorReason(MidasContext ctx) {
        if (ctx == null) {
            return null;
        }
        String lastError = ctx.getLastErrorMessage();
        if (lastError != null && !lastError.isBlank()) {
            return lastError.strip();
        }
        return ctx.safeAuditLog().stream()
                .filter(entry -> entry.getSeverity() == AuditEntry.Severity.ERROR)
                .reduce((first, second) -> second)
                .map(AuditEntry::getDetail)
                .filter(detail -> detail != null && !detail.isBlank())
                .map(String::strip)
                .orElse(null);
    }

    private static String escapeHtml(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    static boolean isRemediationPass(MidasContext ctx) {
        return ctx != null
                && ctx.getProductReviewRemediationAttempts() > 0
                && ctx.getRemediationDirectiveOpt().isPresent();
    }

    static String renderRemediationInProgress(MidasContext ctx) {
        int attempt = ctx.getProductReviewRemediationAttempts();
        int maxAttempts = remediationMaxAttempts(ctx);
        return HEADER + "[🟩🟩🟩🟩⬜⬜⬜]\n\n" +
                "⚠️ <b>Контролер выявил недочеты.</b>\n" +
                "Запущен цикл автоматического исправления (Попытка " + attempt + " из " + maxAttempts + ")...\n\n" +
                "<i>Агент 4 — Корректировка исходного кода...</i>";
    }

    private static int remediationMaxAttempts(MidasContext ctx) {
        JsonNode directive = ctx.getRemediationDirective();
        if (directive != null && directive.has("max_remediation_attempts")) {
            return directive.path("max_remediation_attempts").asInt(PipelineTopology.MAX_PRODUCT_REVIEW_REMEDIATIONS);
        }
        return PipelineTopology.MAX_PRODUCT_REVIEW_REMEDIATIONS;
    }

    public static String renderFinalCompletion(MidasContext ctx, boolean documentDelivered) {
        StringBuilder sb = new StringBuilder();
        sb.append(HEADER).append("[🟩🟩🟩🟩🟩🟩🟩]\n\n");
        sb.append("✅ <b>Пайплайн успешно завершен!</b>\n");
        sb.append("Все 7 артефактов сгенерированы и валидированы.\n");
        if (ctx.getProductReviewRemediationAttempts() > 0) {
            sb.append("🔧 <i>Успешно исправлено автоматически (попытка ")
                    .append(ctx.getProductReviewRemediationAttempts())
                    .append(" из ")
                    .append(remediationMaxAttempts(ctx))
                    .append(").</i>\n");
        }
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
            if (!NOT_MODIFIED_ERROR.equals(e.getApiResponse())) {
                log.warn("[TelegramStateListener] API error updating message [{}] in chat [{}]: {}",
                        messageId, chatId, e.getApiResponse());
            }
        } catch (TelegramApiException e) {
            log.warn("[TelegramStateListener] Failed to edit Telegram message [{}]: {}",
                    messageId, e.getMessage());
        } catch (Exception e) {
            log.error("[TelegramStateListener] Unexpected error editing message [{}]: {}",
                    messageId, e.getMessage(), e);
        }
    }
}

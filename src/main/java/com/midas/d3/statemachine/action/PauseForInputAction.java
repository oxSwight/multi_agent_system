package com.midas.d3.statemachine.action;

import com.midas.d3.context.MidasContext;
import com.midas.d3.statemachine.HumanInTheLoopRegistry;
import com.midas.d3.statemachine.MidasEvent;
import com.midas.d3.statemachine.MidasState;
import com.midas.d3.statemachine.PipelineContextKeys;
import com.midas.d3.telegram.TelegramPipelineBot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.statemachine.StateContext;
import org.springframework.statemachine.action.Action;
import org.springframework.stereotype.Component;

/**
 * State-entry {@link Action} for {@link MidasState#WAITING_FOR_USER_INPUT}.
 *
 * <h2>Responsibility</h2>
 * <ol>
 *   <li>Reads the analyst's clarifying questions from {@link PipelineContextKeys#ANALYST_QUESTIONS_KEY}.</li>
 *   <li>Strips the {@code [NEED_INFO]} prefix to produce clean, user-facing text.</li>
 *   <li>Sends the questions to the user's Telegram chat via
 *       {@link TelegramPipelineBot#sendHtmlMessage} with a call-to-action to reply.</li>
 *   <li>Registers the run as "waiting" in {@link HumanInTheLoopRegistry} so the bot can
 *       resolve the run ID when the user's Reply message arrives.</li>
 * </ol>
 *
 * <h2>Telegram availability</h2>
 * {@link TelegramPipelineBot} is conditionally created ({@code midas.telegram.enabled=true}).
 * If the bot bean is absent (REST-only mode), this action logs a warning and returns without
 * sending anything — the machine remains in {@code WAITING_FOR_USER_INPUT} until explicitly
 * driven via the REST API.
 */
@Slf4j
@Component
public class PauseForInputAction implements Action<MidasState, MidasEvent> {

    private static final String NEED_INFO_PREFIX_PATTERN = "(?i)\\[NEED_INFO]\\s*";

    private final HumanInTheLoopRegistry registry;
    private final ObjectProvider<TelegramPipelineBot> telegramBotProvider;

    public PauseForInputAction(HumanInTheLoopRegistry registry,
                               ObjectProvider<TelegramPipelineBot> telegramBotProvider) {
        this.registry = registry;
        this.telegramBotProvider = telegramBotProvider;
    }

    @Override
    public void execute(StateContext<MidasState, MidasEvent> ctx) {
        MidasContext context = (MidasContext) ctx.getExtendedState()
                .getVariables().get(PipelineContextKeys.MIDAS_CONTEXT);

        if (context == null) {
            log.error("[PauseForInputAction] MidasContext is null — cannot send questions.");
            return;
        }

        String questionsRaw = (String) ctx.getExtendedState()
                .getVariables().get(PipelineContextKeys.ANALYST_QUESTIONS_KEY);

        if (questionsRaw == null || questionsRaw.isBlank()) {
            log.warn("[PauseForInputAction] No questions found in ExtendedState for run [{}].",
                    context.getPipelineRunId());
            return;
        }

        String cleanedQuestions = questionsRaw.replaceFirst(NEED_INFO_PREFIX_PATTERN, "").strip();

        Long chatId = context.getTelegramChatId();
        if (chatId == null) {
            log.warn("[PauseForInputAction] No Telegram chat ID in context for run [{}] — cannot pause via Telegram.",
                    context.getPipelineRunId());
            return;
        }

        TelegramPipelineBot telegramBot = telegramBotProvider.getIfAvailable();
        if (telegramBot == null) {
            log.warn("[PauseForInputAction] Telegram bot is not active — run [{}] is paused but no message was sent.",
                    context.getPipelineRunId());
            return;
        }

        // Register waiting run BEFORE sending the message to avoid a race condition
        // where the user replies before the registry entry exists.
        registry.register(chatId, context.getPipelineRunId());

        String html = "🤔 <b>Системный Аналитик задаёт уточняющие вопросы:</b>\n\n"
                + escapeHtml(cleanedQuestions)
                + "\n\n"
                + "⚠️ <b>Ответьте на это сообщение (Reply), чтобы продолжить генерацию.</b>";

        telegramBot.sendHtmlMessage(chatId, html);

        log.info("[PauseForInputAction] Questions sent to chat [{}] for run [{}]. Pipeline paused.",
                chatId, context.getPipelineRunId());
    }

    private String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}

package com.midas.d3.telegram;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

/**
 * Registers the {@link TelegramPipelineBot} with the Telegram API when
 * {@code midas.telegram.enabled=true}.
 *
 * <p>Conditional registration prevents startup failures when no bot token is configured
 * (e.g. in CI, local development, or when using the pipeline via REST only).
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "midas.telegram", name = "enabled", havingValue = "true")
public class TelegramBotConfig {

    @Bean
    public TelegramBotsApi telegramBotsApi(TelegramPipelineBot bot) throws TelegramApiException {
        TelegramBotsApi api = new TelegramBotsApi(DefaultBotSession.class);
        api.registerBot(bot);
        log.info("[TelegramBotConfig] Bot '{}' registered with Telegram API.", bot.getBotUsername());
        return api;
    }
}

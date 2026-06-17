package com.midas.d3.telegram;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

/**
 * Typed configuration for the MIDAS Telegram bot.
 *
 * <p>Populated from {@code midas.telegram.*} in {@code application.yml}.
 * Set {@code midas.telegram.enabled=true} (or env {@code MIDAS_TELEGRAM_ENABLED=true})
 * to activate the bot. When disabled (default), no bot session is started and all
 * Telegram beans are skipped.
 *
 * <h2>Whitelist</h2>
 * {@code allowed-chat-ids} is an optional set of Telegram chat IDs. When non-empty,
 * only chats whose ID appears in the set may trigger the pipeline. All others receive
 * a "⛔️ Доступ запрещен" reply and are silently dropped. An empty set (default) means
 * <em>allow all</em>.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "midas.telegram")
public class TelegramBotProperties {

    /** Whether the Telegram bot is enabled. Defaults to {@code false}. */
    private boolean enabled = false;

    /** Telegram bot username (without @). */
    private String botUsername = "midas_pipeline_bot";

    /** Telegram bot token issued by @BotFather. */
    private String botToken = "";

    /**
     * Whitelist of Telegram chat IDs allowed to use the bot.
     * An empty set (default) means <em>all chats are permitted</em>.
     *
     * <p>YAML example:
     * <pre>
     * midas.telegram.allowed-chat-ids:
     *   - 123456789
     *   - 987654321
     * </pre>
     */
    private Set<Long> allowedChatIds = new HashSet<>();
}

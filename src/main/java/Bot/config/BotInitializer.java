package Bot.config;

import Bot.telegram.TelegramLogAppender;
import Bot.telegram.MessageSender;
import Bot.telegram.TelegramBot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

/**
 * Регистрирует Telegram-бота в API после того, как Spring полностью поднялся.
 */
@Slf4j
@Configuration      // или @Component
@RequiredArgsConstructor
public class BotInitializer {

    private final TelegramBot bot;
    private final MessageSender messageSender;
    private final BotConfig botConfig;

    @EventListener(ContextRefreshedEvent.class)
    public void init() {
        try {
            TelegramBotsApi api = new TelegramBotsApi(DefaultBotSession.class);
            api.registerBot(bot);

            long adminChatId = parseAdminChatId(botConfig.getAdminChatId());
            TelegramLogAppender.init(messageSender, adminChatId);

            if (adminChatId == 0L) {
                log.warn("admin.chat.id не задан или некорректен — отправка ошибок в Telegram отключена");
            }
            log.info("Telegram bot registered and log appender initialized");
        } catch (TelegramApiException e) {
            log.error("Failed to register Telegram bot", e);
        }
    }

    /** Безопасно парсит chatId администратора; при ошибке возвращает 0 (= отключено). */
    private long parseAdminChatId(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            log.warn("Не удалось разобрать admin.chat.id: '{}'", raw);
            return 0L;
        }
    }
}

package Bot.config;

import Bot.telegram.TelegramLogAppender;
import Bot.telegram.MessageSender;
import Bot.telegram.TelegramBot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

/**
 * Регистрирует Telegram-бота в API после того, как Spring полностью поднялся.
 *
 * <p>Приёмник апдейтов есть не в каждом процессе: домашняя половина отправляет
 * результаты, но апдейты не читает — их читает бот на VPS. Поэтому бин
 * запрашивается через {@link ObjectProvider}: нет его — регистрировать нечего,
 * и это нормальный режим, а не сбой. Отправка ошибок администратору при этом
 * настраивается всегда: она идёт через {@code MessageSender} и long polling
 * не требует.</p>
 */
@Slf4j
@Configuration      // или @Component
@RequiredArgsConstructor
public class BotInitializer {

    private final ObjectProvider<TelegramBot> bot;
    private final MessageSender messageSender;
    private final BotConfig botConfig;

    @EventListener(ContextRefreshedEvent.class)
    public void init() {
        long adminChatId = parseAdminChatId(botConfig.getAdminChatId());
        TelegramLogAppender.init(messageSender, adminChatId);
        if (adminChatId == 0L) {
            log.warn("admin.chat.id не задан или некорректен — отправка ошибок в Telegram отключена");
        }

        TelegramBot polling = bot.getIfAvailable();
        if (polling == null) {
            log.info("Приём апдейтов в этом процессе не поднят — работает только отправка");
            return;
        }

        try {
            TelegramBotsApi api = new TelegramBotsApi(DefaultBotSession.class);
            api.registerBot(polling);
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

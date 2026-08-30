package Bot.config;

/**
 * Конфигурация параметров Telegram-бота.
 *
 * <p>Хранит имя бота, токен и chatId администратора, заполняемые из
 * application.properties и/или переменных окружения. Используется классом
 * {@code TelegramBot} для инициализации и аутентификации в API Telegram.</p>
 */
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;

@Configuration
@Data
@Slf4j
public class BotConfig {
    @Value("${bot.name}")
    String botName ;
    @Value("${bot.key}")
    String botToken ;

    @Value("${admin.chat.id}")
    String adminChatId;

    @PostConstruct
    public void logConfig() {
        log.info("=== Конфигурация бота ===");
        log.info("Bot Name: {}", botName);
        // Только номер бота (часть до двоеточия) — он и так виден всем.
        // Первые десять символов прихватывали и начало секрета
        log.info("Bot Token: {}", botToken == null ? "NULL"
                : botToken.contains(":") ? botToken.substring(0, botToken.indexOf(':')) + ":…"
                : "задан");
        log.info("Admin Chat ID: {}", adminChatId);
        log.info("=========================");
    }
}

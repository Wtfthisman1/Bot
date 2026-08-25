package Bot;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * Проверяет, что контекст собирается целиком: все бины разрешают зависимости
 * и {@code @Value}-свойства.
 *
 * <p>Токен здесь заведомо нерабочий. Регистрация в Telegram API не удастся,
 * но {@link Bot.config.BotInitializer} и {@link Bot.telegram.TelegramBot}
 * логируют такую ошибку и не роняют контекст — это ровно то поведение,
 * на которое они рассчитаны.</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "bot.key=111:TEST_TOKEN_NOT_REAL",
        "admin.chat.id=",
        "cleanup.enabled=false",
        // -1 отключает отдельный management-порт: в тестах он не нужен,
        // а 8081 может быть занят рабочим экземпляром бота
        "management.server.port=-1"
})
class SpringBotApplicationTests {

    @Test
    void contextLoads() {
    }
}

package Bot.insight;

/**
 * Сборка клиента к языковой модели.
 *
 * <p>Модель — вещь необязательная: дома она есть, на VPS её нет и не будет, а
 * у того, кто поднимет бота у себя, может не быть видеокарты вовсе. Поэтому
 * бин здесь есть всегда, но при выключенной обработке это заглушка, которая
 * честно отвечает «недоступна». Кнопки на странице показываются по её ответу,
 * и ничего чинить ради их отсутствия не приходится.</p>
 */
import Bot.config.Profiles;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.time.Duration;

@Profile(Profiles.HOME)
@Configuration
@Slf4j
public class InsightConfig {

    @Bean
    LanguageModel languageModel(
            @Value("${insight.enabled:false}") boolean enabled,
            @Value("${insight.url:http://127.0.0.1:11434}") String url,
            @Value("${insight.model:qwen2.5:7b-instruct}") String model,
            @Value("${insight.num-ctx:8192}") int contextTokens,
            @Value("${insight.timeout-minutes:20}") int timeoutMinutes,
            @Value("${insight.keep-alive-minutes:5}") int keepAliveMinutes) {

        if (!enabled) {
            log.info("Обработка текста выключена (insight.enabled)");
            return new Unavailable();
        }
        return new OllamaModel(url, model, contextTokens,
                Duration.ofMinutes(timeoutMinutes), Duration.ofMinutes(keepAliveMinutes));
    }

    /** Модели нет: заказать обработку нельзя, всё остальное работает как прежде. */
    static final class Unavailable implements LanguageModel {

        @Override
        public boolean available() {
            return false;
        }

        @Override
        public String name() {
            return "—";
        }

        @Override
        public String ask(String instruction, String prompt) {
            throw new IllegalStateException("Обработка текста выключена");
        }

        @Override
        public void unload() {
            // нечего выгружать
        }
    }
}

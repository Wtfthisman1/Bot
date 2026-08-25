package Bot.home;

/**
 * Сборка клиента к дому для процесса, где домашней половины нет.
 *
 * <p>Ответственность: собрать транспорт и решить, оборачивать ли его спулом.
 * Отдельная конфигурация нужна потому, что оба класса — {@link HttpHomeApi} и
 * {@link SpoolingHomeApi} — реализуют {@link HomeApi}: будь они обычными
 * бинами, Spring не знал бы, какой из них внедрять.</p>
 */
import Bot.config.Profiles;
import Bot.home.spool.SpoolingHomeApi;
import Bot.home.spool.TaskSpool;
import Bot.telegram.MessageSender;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.time.Duration;

@Profile("!" + Profiles.HOME)
@Configuration(proxyBeanMethods = false)
@Slf4j
public class HomeApiConfig {

    @Bean
    public HomeApi homeApi(@Value("${home.api.base-url:}") String baseUrl,
                           @Value("${home.api.key:}") String key,
                           @Value("${spool.enabled:true}") boolean spoolEnabled,
                           @Value("${spool.max-tasks:500}") int capacity,
                           @Value("${spool.max-age-days:3}") int maxAgeDays,
                           TaskSpool spool,
                           MessageSender messageSender) {
        HttpHomeApi transport = new HttpHomeApi(baseUrl, key);
        if (!spoolEnabled) {
            log.info("Спул выключен: при спящем доме задачи приниматься не будут");
            return transport;
        }
        log.info("Задачи при спящем доме откладываются: не больше {} и не дольше {} суток",
                capacity, maxAgeDays);
        return new SpoolingHomeApi(transport, spool, messageSender,
                capacity, Duration.ofDays(maxAgeDays));
    }
}

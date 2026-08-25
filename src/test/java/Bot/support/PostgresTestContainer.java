package Bot.support;

/**
 * Настоящий Postgres в контейнере для тестов, которым нужна база.
 *
 * <p>Именно Postgres, а не встраиваемая база: очередь опирается на
 * {@code FOR UPDATE SKIP LOCKED}, которого у H2 нет. Тесты на подмене были бы
 * зелёными ровно до первого запуска на проде.</p>
 *
 * <p>Контейнер статический и не останавливается между классами тестов:
 * поднимать Postgres заново на каждый класс — минуты вместо секунд.
 * Testcontainers погасит его, когда закончится JVM.</p>
 */
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;

@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestContainer {

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgres() {
        return POSTGRES;
    }
}

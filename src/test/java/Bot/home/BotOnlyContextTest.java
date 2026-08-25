package Bot.home;

/**
 * Бот без домашней половины обязан подниматься на машине без базы.
 *
 * <p>Ровно ради этого и затевалось разделение: на VPS 960 МБ памяти, и
 * Postgres там не будет. Проверка дешёвая, а ломается такое незаметно — любая
 * забытая аннотация {@code @Profile} на домашнем бине снова притянет за собой
 * репозитории и уронит старт уже на сервере.</p>
 */
import Bot.processing.JobStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("bot")
@TestPropertySource(properties = {
        "bot.key=111:TEST_TOKEN_NOT_REAL",
        "admin.chat.id=",
        "management.server.port=-1",
        "home.api.base-url=http://10.8.0.2:8080",
        "home.api.key=test-key"
})
class BotOnlyContextTest {

    @Autowired private ApplicationContext context;

    @Test
    void talksToHomeOverHttp() {
        assertThat(context.getBean(HomeApi.class)).isInstanceOf(HttpHomeApi.class);
    }

    @Test
    void hasNoDatabaseAtAll() {
        assertThatThrownBy(() -> context.getBean(DataSource.class))
                .isInstanceOf(NoSuchBeanDefinitionException.class);
        assertThatThrownBy(() -> context.getBean(JobStore.class))
                .isInstanceOf(NoSuchBeanDefinitionException.class);
    }
}

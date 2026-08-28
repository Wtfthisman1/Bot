package Bot.home;

/**
 * Провод между ботом и домом целиком: HTTP-клиент против живого контроллера.
 *
 * <p>Проверяется то, что не поймает компилятор: адреса, тела запросов и
 * заголовок с ключом должны совпасть у двух сторон, которые после разделения
 * живут на разных машинах и обновляются по отдельности.</p>
 */
import Bot.insight.InsightKind;
import Bot.owner.Owner;
import Bot.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestClientResponseException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("home")
@Import(PostgresTestContainer.class)
@TestPropertySource(properties = {
        "bot.key=111:TEST_TOKEN_NOT_REAL",
        "admin.chat.id=",
        "cleanup.enabled=false",
        "management.server.port=-1",
        "home.api.key=test-key"
})
class HomeApiOverHttpIT {

    private static final Owner OWNER = Owner.telegram(4242L);

    @LocalServerPort private int port;

    private HomeApi client(String key) {
        return new HttpHomeApi("http://localhost:" + port, key);
    }

    @Test
    void enqueuedLinkIsVisibleInStatus() {
        HomeApi home = client("test-key");

        home.transcribeLink(OWNER, "https://youtu.be/dQw4w9WgXcQ");

        assertThat(home.status(OWNER).queued()).isEqualTo(1);
    }

    @Test
    void uploadFormLinkComesBack() {
        assertThat(client("test-key").uploadFormLink(OWNER)).contains("/upload/");
    }

    /**
     * Чужой ключ — 403, и задача не ставится.
     *
     * <p>Ключ здесь латиницей не для красоты: заголовок с кириллицей до
     * приложения не доходит вовсе — его отсекает брандмауэр Spring Security,
     * отвечая 400. Настоящие ключи — base64 от {@code openssl rand}, то есть
     * ровно тот набор символов, что и здесь.</p>
     */
    @Test
    void wrongKeyIsRejected() {
        assertThatThrownBy(() -> client("not-the-key").transcribeLink(OWNER, "https://vimeo.com/1"))
                .isInstanceOf(RestClientResponseException.class)
                .hasMessageContaining("403");
    }

    /**
     * Заказ обработки по чужой задаче: дом отказывает словами, а не пятисоткой.
     *
     * <p>Проверяется весь провод: тело запроса вместе с видом обработки и темой,
     * адрес и обратный путь отказа. Задача здесь заведомо не наша — выдуманный
     * id, — и это единственный отказ, который виден без живой модели.</p>
     */
    @Test
    void insightOnAForeignJobIsRefusedInWords() {
        HomeApi home = client("test-key");
        String foreign = UUID.randomUUID().toString();

        assertThat(home.orderInsight(OWNER, foreign, InsightKind.SUMMARY, null))
                .get().asString().contains("недоступна");
        assertThat(home.orderInsight(OWNER, foreign, InsightKind.TOPIC, "сроки"))
                .get().asString().contains("недоступна");
    }

    /** Дом выключен: клиент обязан назвать это своим именем, а не общей ошибкой. */
    @Test
    void sleepingHomeIsRecognisable() {
        HomeApi unreachable = new HttpHomeApi("http://localhost:1", "test-key");

        assertThatThrownBy(() -> unreachable.transcribeLink(OWNER, "https://vimeo.com/1"))
                .isInstanceOf(HomeUnavailableException.class);
    }
}

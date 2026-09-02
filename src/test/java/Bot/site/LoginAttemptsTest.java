package Bot.site;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ограничение перебора паролей: до него вход упирался только в скорость bcrypt.
 */
class LoginAttemptsTest {

    private final LoginAttempts attempts = new LoginAttempts();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(attempts, "maxAttempts", 3);
        ReflectionTestUtils.setField(attempts, "windowMinutes", 15);
        ReflectionTestUtils.setField(attempts, "maxRegistrations", 2);
        ReflectionTestUtils.setField(attempts, "registrationWindowMinutes", 60);
    }

    @Test
    void blocksAfterTooManyFailures() {
        MockHttpServletRequest request = from("203.0.113.7");

        assertThat(attempts.allows(request)).isTrue();
        attempts.failed(request);
        attempts.failed(request);
        assertThat(attempts.allows(request)).isTrue();

        attempts.failed(request);
        assertThat(attempts.allows(request)).isFalse();
    }

    @Test
    void successForgetsThePast() {
        MockHttpServletRequest request = from("203.0.113.7");
        attempts.failed(request);
        attempts.failed(request);
        attempts.succeeded(request);
        attempts.failed(request);

        assertThat(attempts.allows(request)).isTrue();
    }

    /**
     * Удачная регистрация тоже тратит попытку.
     *
     * <p>Считались только неудачи, и один адрес мог штамповать аккаунты без
     * счёта. Каждый новый аккаунт — это ещё три бесплатные расшифровки, то
     * есть чужое время на видеокарте: квота по аккаунту не значит ничего, пока
     * аккаунты бесплатны и бесконечны.</p>
     */
    @Test
    void successfulRegistrationsAreCountedToo() {
        MockHttpServletRequest request = from("203.0.113.7");

        attempts.spend(request);
        attempts.spend(request);
        assertThat(attempts.allows(request)).isTrue();

        attempts.spend(request);
        assertThat(attempts.allows(request)).isFalse();
    }

    /**
     * У регистрации свой, более строгий счёт.
     *
     * <p>Форма регистрации отвечает «на эту почту аккаунт уже заведён», и по
     * этому ответу перебором узнают, кто здесь есть. Убрать сам ответ нечем,
     * пока писем слать нечем, — поэтому ограничена скорость перебора.</p>
     */
    @Test
    void registrationHasItsOwnBudget() {
        MockHttpServletRequest request = from("203.0.113.7");

        assertThat(attempts.allowsRegistration(request)).isTrue();
        attempts.spendRegistration(request);
        assertThat(attempts.allowsRegistration(request)).isTrue();
        attempts.spendRegistration(request);

        assertThat(attempts.allowsRegistration(request)).isFalse();
        // Вход при этом не заперт: счётчики разные
        assertThat(attempts.allows(request)).isTrue();
    }

    /** Удачный вход прощает неудачные — но не съеденные регистрации. */
    @Test
    void successfulLoginDoesNotRefillRegistrations() {
        MockHttpServletRequest request = from("203.0.113.7");
        attempts.spendRegistration(request);
        attempts.spendRegistration(request);

        attempts.succeeded(request);

        assertThat(attempts.allowsRegistration(request)).isFalse();
    }

    @Test
    void countsEachSourceSeparately() {
        MockHttpServletRequest guilty = from("203.0.113.7");
        for (int i = 0; i < 3; i++) {
            attempts.failed(guilty);
        }

        // Иначе первый же перебор запирал бы вход всем сразу
        assertThat(attempts.allows(guilty)).isFalse();
        assertThat(attempts.allows(from("198.51.100.1"))).isTrue();
    }

    @Test
    void takesTheClientAddressFromTheProxyHeader() {
        // За nginx у всех запросов один и тот же remoteAddr — адрес туннеля
        MockHttpServletRequest first = from("10.8.0.1");
        first.addHeader("X-Real-IP", "203.0.113.7");
        MockHttpServletRequest second = from("10.8.0.1");
        second.addHeader("X-Real-IP", "198.51.100.1");

        for (int i = 0; i < 3; i++) {
            attempts.failed(first);
        }

        assertThat(attempts.allows(first)).isFalse();
        assertThat(attempts.allows(second)).isTrue();
    }

    @Test
    void doesNotLetTheClientChooseItsOwnKey() {
        // X-Forwarded-For клиент присылает сам, nginx лишь дописывает в конец:
        // считая ключом присланное, ограничитель обходился бы одной строкой
        MockHttpServletRequest request = from("10.8.0.1");
        request.addHeader("X-Real-IP", "203.0.113.7");
        request.addHeader("X-Forwarded-For", "я-каждый-раз-новый, 203.0.113.7");
        for (int i = 0; i < 3; i++) {
            attempts.failed(request);
        }

        MockHttpServletRequest another = from("10.8.0.1");
        another.addHeader("X-Real-IP", "203.0.113.7");
        another.addHeader("X-Forwarded-For", "и-теперь-другой, 203.0.113.7");

        assertThat(attempts.allows(another)).isFalse();
    }

    private static MockHttpServletRequest from(String address) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(address);
        return request;
    }
}

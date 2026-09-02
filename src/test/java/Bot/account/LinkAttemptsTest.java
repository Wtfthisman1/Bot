package Bot.account;

/**
 * Подбор кода привязки: пять промахов — и чат ждёт.
 *
 * <p>Проверяется то, чего не было вовсе: код привязки отдаёт чужую переписку
 * целиком (привязанный чат входит в аккаунт как в свой), а приходит он командой
 * из Telegram — мимо nginx, где считаются попытки входа на сайт.</p>
 */
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LinkAttemptsTest {

    private static final long CHAT = REDACTED_CHAT_IDL;

    private final LinkAttempts attempts = new LinkAttempts();

    @Test
    void holdsTheChatAfterTooManyMisses() {
        for (int i = 0; i < 4; i++) {
            attempts.failed(CHAT);
            assertThat(attempts.allows(CHAT)).isTrue();
        }

        attempts.failed(CHAT);
        assertThat(attempts.allows(CHAT)).isFalse();
    }

    @Test
    void countsEachChatSeparately() {
        for (int i = 0; i < 5; i++) {
            attempts.failed(CHAT);
        }

        assertThat(attempts.allows(CHAT)).isFalse();
        assertThat(attempts.allows(CHAT + 1)).isTrue();
    }

    /** Код подошёл — значит, это не подбор: счётчик обнуляется. */
    @Test
    void successForgetsThePast() {
        for (int i = 0; i < 4; i++) {
            attempts.failed(CHAT);
        }
        attempts.succeeded(CHAT);

        for (int i = 0; i < 4; i++) {
            attempts.failed(CHAT);
        }
        assertThat(attempts.allows(CHAT)).isTrue();
    }
}

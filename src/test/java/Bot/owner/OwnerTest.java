package Bot.owner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Форма идентификатора владельца — это защита файлового хранилища.
 *
 * <p>Из {@code storageKey()} собирается путь, а сам владелец приезжает снаружи:
 * в теле запроса к {@code /internal} и в записи спула. Пока проверки не было,
 * {@code id} вида {@code ../../..} уводил запись за пределы каталога.</p>
 */
class OwnerTest {

    @Test
    void acceptsRealIdentifiers() {
        assertThat(Owner.telegram(REDACTED_CHAT_IDL).storageKey()).isEqualTo("REDACTED_CHAT_ID");
        assertThat(Owner.telegram(-100REDACTED_CHAT_ID0L).storageKey()).isEqualTo("-100REDACTED_CHAT_ID0");
        assertThat(Owner.account("d21e18a1-7381-4b76-8306-44b9f7a0c0e3").storageKey())
                .isEqualTo("acc-d21e18a1-7381-4b76-8306-44b9f7a0c0e3");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "../../../home/dmitry/.ssh",
            "..",
            "/etc/passwd",
            "d21e18a1-7381-4b76-8306-44b9f7a0c0e3/../../..",
            "не uuid",
            "REDACTED_CHAT_ID0"          // число — это форма чата, а не аккаунта
    })
    void rejectsAnythingButUuidForAccount(String id) {
        assertThatThrownBy(() -> new Owner(Owner.OwnerType.ACCOUNT, id))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "../../etc",
            "REDACTED_CHAT_ID/../../..",
            "d21e18a1-7381-4b76-8306-44b9f7a0c0e3",   // uuid — это форма аккаунта
            "REDACTED_CHAT_ID0REDACTED_CHAT_ID0"                     // длиннее любого chat id
    })
    void rejectsAnythingButNumberForChat(String id) {
        assertThatThrownBy(() -> new Owner(Owner.OwnerType.TELEGRAM, id))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsEmpty() {
        assertThatThrownBy(() -> new Owner(Owner.OwnerType.TELEGRAM, "  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void doesNotLeakTheIdIntoTheMessage() {
        // Сообщение уходит в журнал, а оттуда админу в чат: чужой строке там не место
        assertThatThrownBy(() -> new Owner(Owner.OwnerType.ACCOUNT, "<script>alert(1)</script>"))
                .hasMessageNotContaining("script");
    }
}

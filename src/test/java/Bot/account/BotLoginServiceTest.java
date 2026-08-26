package Bot.account;

/**
 * Вход через бота: код ждёт подтверждения, срабатывает один раз и приводит
 * в тот же аккаунт, что и виджет Telegram.
 */
import Bot.support.PostgresTestContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PostgresTestContainer.class, AccountService.class, BotLoginService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class BotLoginServiceTest {

    private static final long CHAT_ID = REDACTED_CHAT_IDL;

    @Autowired BotLoginService logins;
    @Autowired AccountService accounts;
    @Autowired BotLoginCodeRepository codes;
    @Autowired AccountRepository accountRepository;

    @AfterEach
    void clean() {
        codes.deleteAll();
        accountRepository.deleteAll();
    }

    @Test
    void freshCodeWaitsForConfirmation() {
        String code = logins.issue();

        assertThat(logins.stateOf(code)).isEqualTo(BotLoginService.State.WAITING);
        assertThat(logins.claim(code)).isEmpty();
    }

    @Test
    void confirmedCodeLetsTheBrowserIn() {
        String code = logins.issue();

        assertThat(logins.confirm(CHAT_ID, code, "Аня")).isTrue();
        assertThat(logins.stateOf(code)).isEqualTo(BotLoginService.State.CONFIRMED);
        assertThat(logins.claim(code)).isPresent();
    }

    /** Второй браузер по тому же коду войти не должен. */
    @Test
    void codeWorksExactlyOnce() {
        String code = logins.issue();
        logins.confirm(CHAT_ID, code, "Аня");

        assertThat(logins.claim(code)).isPresent();
        assertThat(logins.claim(code)).isEmpty();
        assertThat(logins.stateOf(code)).isEqualTo(BotLoginService.State.UNKNOWN);
    }

    /** Вход через бота и вход виджетом — одна и та же учётная запись. */
    @Test
    void botLoginLandsInTheSameAccountAsTheWidget() {
        AccountService.Account viaWidget = accounts.findOrCreateByIdentity(
                IdentityProvider.TELEGRAM, String.valueOf(CHAT_ID), "Аня", null);

        String code = logins.issue();
        logins.confirm(CHAT_ID, code, "Аня");

        assertThat(logins.claim(code))
                .map(AccountService.Account::id)
                .contains(viaWidget.id());
        assertThat(accountRepository.count()).isEqualTo(1);
    }

    @Test
    void unknownCodeIsRefused() {
        assertThat(logins.confirm(CHAT_ID, "нет-такого", null)).isFalse();
        assertThat(logins.stateOf("нет-такого")).isEqualTo(BotLoginService.State.UNKNOWN);
    }

    /** Код в открытой вкладке не должен жить вечно. */
    @Test
    void expiredCodeIsRefusedEvenBeforeConfirmation() {
        String code = logins.issue();
        BotLoginCodeEntity entity = codes.findById(code).orElseThrow();
        entity.setExpiresAt(Instant.now().minus(Duration.ofMinutes(1)));
        codes.save(entity);

        assertThat(logins.stateOf(code)).isEqualTo(BotLoginService.State.EXPIRED);
        assertThat(logins.confirm(CHAT_ID, code, "Аня")).isFalse();
        assertThat(logins.claim(code)).isEmpty();
    }
}

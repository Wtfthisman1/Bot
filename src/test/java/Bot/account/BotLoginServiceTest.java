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
        BotLoginService.Issued issued = logins.issue();

        assertThat(logins.stateOf(issued.code())).isEqualTo(BotLoginService.State.WAITING);
        assertThat(logins.claim(issued.code())).isEmpty();
    }

    @Test
    void confirmedCodeLetsTheBrowserIn() {
        BotLoginService.Issued issued = logins.issue();

        assertThat(logins.confirm(CHAT_ID, issued.code(), "Аня", issued.checkNumber()))
                .isEqualTo(BotLoginService.Confirmation.CONFIRMED);
        assertThat(logins.stateOf(issued.code())).isEqualTo(BotLoginService.State.CONFIRMED);
        assertThat(logins.claim(issued.code())).isPresent();
    }

    /**
     * Главное свойство сверки: подтвердить вход может только тот, кто видит
     * страницу. Ссылку можно прислать постороннему под любым предлогом, и
     * раньше ему хватало нажать «Это я».
     */
    @Test
    void wrongNumberBurnsTheCode() {
        BotLoginService.Issued issued = logins.issue();
        int wrong = issued.checkNumber() == 42 ? 43 : 42;

        assertThat(logins.confirm(CHAT_ID, issued.code(), "Аня", wrong))
                .isEqualTo(BotLoginService.Confirmation.WRONG_NUMBER);

        // Второй попытки нет: у того, кому прислали чужую ссылку, её быть не должно
        assertThat(logins.confirm(CHAT_ID, issued.code(), "Аня", issued.checkNumber()))
                .isEqualTo(BotLoginService.Confirmation.STALE);
        assertThat(logins.claim(issued.code())).isEmpty();
    }

    @Test
    void challengeContainsTheRealNumberAmongOthers() {
        BotLoginService.Issued issued = logins.issue();

        var numbers = logins.challengeFor(issued.code());
        assertThat(numbers).hasSize(3).doesNotHaveDuplicates()
                .contains(issued.checkNumber());
        assertThat(numbers).allMatch(n -> n >= 10 && n <= 99);
    }

    @Test
    void challengeForAnUnknownCodeTellsNothing() {
        assertThat(logins.challengeFor("нет-такого")).isEmpty();
    }

    /** Второй браузер по тому же коду войти не должен. */
    @Test
    void codeWorksExactlyOnce() {
        BotLoginService.Issued issued = logins.issue();
        logins.confirm(CHAT_ID, issued.code(), "Аня", issued.checkNumber());

        assertThat(logins.claim(issued.code())).isPresent();
        assertThat(logins.claim(issued.code())).isEmpty();
        assertThat(logins.stateOf(issued.code())).isEqualTo(BotLoginService.State.UNKNOWN);
    }

    /** Вход через бота и вход виджетом — одна и та же учётная запись. */
    @Test
    void botLoginLandsInTheSameAccountAsTheWidget() {
        AccountService.Account viaWidget = accounts.findOrCreateByIdentity(
                IdentityProvider.TELEGRAM, String.valueOf(CHAT_ID), "Аня", null);

        BotLoginService.Issued issued = logins.issue();
        logins.confirm(CHAT_ID, issued.code(), "Аня", issued.checkNumber());

        assertThat(logins.claim(issued.code()))
                .map(AccountService.Account::id)
                .contains(viaWidget.id());
        assertThat(accountRepository.count()).isEqualTo(1);
    }

    @Test
    void unknownCodeIsRefused() {
        assertThat(logins.confirm(CHAT_ID, "нет-такого", null, 42))
                .isEqualTo(BotLoginService.Confirmation.STALE);
        assertThat(logins.stateOf("нет-такого")).isEqualTo(BotLoginService.State.UNKNOWN);
    }

    /** Код в открытой вкладке не должен жить вечно. */
    @Test
    void expiredCodeIsRefusedEvenBeforeConfirmation() {
        BotLoginService.Issued issued = logins.issue();
        BotLoginCodeEntity entity = codes.findById(issued.code()).orElseThrow();
        entity.setExpiresAt(Instant.now().minus(Duration.ofMinutes(1)));
        codes.save(entity);

        assertThat(logins.stateOf(issued.code())).isEqualTo(BotLoginService.State.EXPIRED);
        assertThat(logins.confirm(CHAT_ID, issued.code(), "Аня", issued.checkNumber()))
                .isEqualTo(BotLoginService.Confirmation.STALE);
        assertThat(logins.claim(issued.code())).isEmpty();
    }
}

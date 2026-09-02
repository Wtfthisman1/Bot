package Bot.account;

/**
 * Вход через бота: ссылку выдаёт чат, она срабатывает один раз и приводит в тот
 * же аккаунт, что и виджет Telegram.
 *
 * <p>Главное свойство здесь — направление. Ссылка заводится для конкретного
 * чата и ведёт только в его аккаунт, поэтому «прислать чужую ссылку» нельзя в
 * принципе: чужой ссылки не существует. Раньше вход начинала страница, а
 * подтверждали его в чате, и проверять приходилось совсем другое — что
 * посторонний не угадает число сверки.</p>
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
    @Autowired BotLoginLinkRepository links;
    @Autowired AccountRepository accountRepository;

    @AfterEach
    void clean() {
        links.deleteAll();
        accountRepository.deleteAll();
    }

    @Test
    void issuedLinkPointsAtThisSite() {
        String link = logins.issue(CHAT_ID, "Аня").orElseThrow();

        assertThat(link).contains("/auth/enter/");
        assertThat(tokenOf(link)).isNotBlank();
    }

    /**
     * Открытие ссылки её не гасит: по адресам ходят предпросмотр Telegram и
     * антивирусы, и вход сгорал бы до того, как человек его увидит.
     */
    @Test
    void lookingAtTheLinkDoesNotBurnIt() {
        String token = tokenOf(logins.issue(CHAT_ID, "Аня").orElseThrow());

        assertThat(logins.nameOf(token)).contains("Аня");
        assertThat(logins.nameOf(token)).contains("Аня");
        assertThat(logins.claim(token)).isPresent();
    }

    /** Второй браузер по той же ссылке войти не должен. */
    @Test
    void linkWorksExactlyOnce() {
        String token = tokenOf(logins.issue(CHAT_ID, "Аня").orElseThrow());

        assertThat(logins.claim(token)).isPresent();
        assertThat(logins.claim(token)).isEmpty();
        assertThat(logins.nameOf(token)).isEmpty();
    }

    /** Ссылка ведёт в аккаунт того чата, который её попросил, — и ничей больше. */
    @Test
    void linkLandsInTheChatsOwnAccount() {
        AccountService.Account mine = accounts.forTelegramChat(CHAT_ID, "Аня");
        String token = tokenOf(logins.issue(CHAT_ID, "Аня").orElseThrow());

        assertThat(logins.claim(token))
                .map(BotLoginService.Entry::account)
                .map(AccountService.Account::id)
                .contains(mine.id());
    }

    /** Вход через бота и вход виджетом — одна и та же учётная запись. */
    @Test
    void botLoginLandsInTheSameAccountAsTheWidget() {
        AccountService.Account viaWidget = accounts.findOrCreateByIdentity(
                IdentityProvider.TELEGRAM, String.valueOf(CHAT_ID), "Аня", null);

        String token = tokenOf(logins.issue(CHAT_ID, "Аня").orElseThrow());

        assertThat(logins.claim(token))
                .map(BotLoginService.Entry::account)
                .map(AccountService.Account::id)
                .contains(viaWidget.id());
        assertThat(accountRepository.count()).isEqualTo(1);
    }

    @Test
    void unknownTokenLetsNobodyIn() {
        assertThat(logins.nameOf("нет-такого")).isEmpty();
        assertThat(logins.claim("нет-такого")).isEmpty();
    }

    /** Ссылка лежит в переписке, поэтому долго жить ей нельзя. */
    @Test
    void expiredLinkIsRefused() {
        String token = tokenOf(logins.issue(CHAT_ID, "Аня").orElseThrow());
        BotLoginLinkEntity entity = links.findById(token).orElseThrow();
        entity.setExpiresAt(Instant.now().minus(Duration.ofMinutes(1)));
        links.save(entity);

        assertThat(logins.nameOf(token)).isEmpty();
        assertThat(logins.claim(token)).isEmpty();
    }

    /** Ссылку нельзя просить без счёта: это запись в базу и сообщение в чат. */
    @Test
    void tooManyLinksAreRefused() {
        for (int i = 0; i < 5; i++) {
            assertThat(logins.issue(CHAT_ID, "Аня")).isPresent();
        }
        assertThat(logins.issue(CHAT_ID, "Аня")).isEmpty();

        // Соседний чат за это не отвечает
        assertThat(logins.issue(CHAT_ID + 1, "Не Аня")).isPresent();
    }

    private static String tokenOf(String link) {
        return link.substring(link.lastIndexOf('/') + 1);
    }
}

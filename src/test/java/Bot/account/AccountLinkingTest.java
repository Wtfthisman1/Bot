package Bot.account;

/**
 * Внешние входы и привязка чата: одна личность — один аккаунт, код срабатывает
 * ровно раз, а после привязки переписка перестаёт быть отдельным человеком.
 */
import Bot.owner.Owner;
import Bot.support.PostgresTestContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PostgresTestContainer.class, AccountService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AccountLinkingTest {

    private static final long CHAT_ID = 908070L;

    @Autowired AccountService accounts;
    @Autowired AccountRepository repository;

    @AfterEach
    void clean() {
        repository.deleteAll();
    }

    /** Второй вход тем же Telegram — это тот же человек, а не новый аккаунт. */
    @Test
    void sameIdentityAlwaysLandsInTheSameAccount() {
        AccountService.Account first = accounts.findOrCreateByIdentity(
                IdentityProvider.TELEGRAM, "42", "Аня", null);
        AccountService.Account second = accounts.findOrCreateByIdentity(
                IdentityProvider.TELEGRAM, "42", "Аня", null);

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(repository.count()).isEqualTo(1);
    }

    /** Аккаунт без почты и пароля — обычное дело: так приходят из Telegram. */
    @Test
    void accountFromTelegramHasNeitherEmailNorPassword() {
        AccountService.Account account = accounts.forTelegramChat(CHAT_ID, "Аня");

        AccountEntity stored = repository.findById(account.id()).orElseThrow();
        assertThat(stored.getEmail()).isNull();
        assertThat(stored.getPasswordHash()).isNull();
        assertThat(account.title()).isEqualTo("Аня");
    }

    @Test
    void linkCodeWorksExactlyOnce() {
        AccountService.Account account = accounts.register("anya@example.com", "очень-длинный-пароль", "Аня");
        String code = accounts.issueLinkCode(account.id());

        assertThat(accounts.redeemLinkCode(code, CHAT_ID)).isPresent();
        assertThat(accounts.redeemLinkCode(code, CHAT_ID)).isEmpty();
    }

    @Test
    void unknownCodeIsRefused() {
        assertThat(accounts.redeemLinkCode("ZZZZZZ", CHAT_ID)).isEmpty();
    }

    /**
     * Чат, успевший завести себе аккаунт молча, при привязке переезжает целиком:
     * два аккаунта на одного человека — это и есть та беда, от которой
     * привязка спасает.
     */
    @Test
    void linkingMovesAChatThatAlreadyHadItsOwnAccount() {
        AccountService.Account silent = accounts.forTelegramChat(CHAT_ID, null);
        AccountService.Account real = accounts.register("anya@example.com", "очень-длинный-пароль", "Аня");

        accounts.redeemLinkCode(accounts.issueLinkCode(real.id()), CHAT_ID);

        assertThat(accounts.ownersOf(real.id()))
                .contains(Owner.telegram(CHAT_ID));
        assertThat(accounts.ownersOf(silent.id()))
                .doesNotContain(Owner.telegram(CHAT_ID));
    }

    /** История аккаунта складывается из его собственных задач и задач его чатов. */
    @Test
    void ownersOfIncludesAccountItselfAndItsChats() {
        AccountService.Account account = accounts.register("anya@example.com", "очень-длинный-пароль", "Аня");
        accounts.redeemLinkCode(accounts.issueLinkCode(account.id()), CHAT_ID);

        assertThat(accounts.ownersOf(account.id()))
                .containsExactlyInAnyOrder(account.asOwner(), Owner.telegram(CHAT_ID));
    }
}

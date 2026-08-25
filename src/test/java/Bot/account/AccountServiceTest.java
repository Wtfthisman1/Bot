package Bot.account;

/**
 * Аккаунты: почта уникальна и регистронезависима, пароль в базе только хешем,
 * а неизвестная почта и неверный пароль отвечают одинаково.
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PostgresTestContainer.class, AccountService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AccountServiceTest {

    private static final String EMAIL = "anya@example.com";
    private static final String PASSWORD = "очень-длинный-пароль";

    @Autowired AccountService accounts;
    @Autowired AccountRepository repository;

    @AfterEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    void registeredAccountCanLogIn() {
        AccountService.Account created = accounts.register(EMAIL, PASSWORD, "Аня");

        assertThat(accounts.authenticate(EMAIL, PASSWORD))
                .map(AccountService.Account::id)
                .contains(created.id());
    }

    /** «Ivan@» и «ivan@» — один человек, который иначе не сможет войти. */
    @Test
    void emailIsCaseInsensitive() {
        accounts.register("Anya@Example.COM ", PASSWORD, "Аня");

        assertThat(accounts.authenticate(EMAIL, PASSWORD)).isPresent();
        assertThatThrownBy(() -> accounts.register(EMAIL, PASSWORD, "Другая"))
                .isInstanceOf(AccountService.EmailTakenException.class);
    }

    @Test
    void wrongPasswordIsRejected() {
        accounts.register(EMAIL, PASSWORD, "Аня");

        assertThat(accounts.authenticate(EMAIL, "не-тот-пароль")).isEmpty();
    }

    /** По разнице ответов подбирают список существующих адресов. */
    @Test
    void unknownEmailLooksExactlyLikeAWrongPassword() {
        accounts.register(EMAIL, PASSWORD, "Аня");

        assertThat(accounts.authenticate("никого@example.com", PASSWORD)).isEmpty();
    }

    @Test
    void passwordIsNeverStoredAsIs() {
        accounts.register(EMAIL, PASSWORD, "Аня");

        AccountEntity stored = repository.findByEmail(EMAIL).orElseThrow();
        assertThat(stored.getPasswordHash()).doesNotContain(PASSWORD).startsWith("$2");
    }

    @Test
    void shortPasswordIsRefused() {
        assertThatThrownBy(() -> accounts.register(EMAIL, "коротко", "Аня"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(repository.count()).isZero();
    }

    @Test
    void successfulLoginIsRemembered() {
        accounts.register(EMAIL, PASSWORD, "Аня");

        accounts.authenticate(EMAIL, PASSWORD);

        assertThat(repository.findByEmail(EMAIL).orElseThrow().getLastLoginAt()).isNotNull();
    }

    /** Задачи такого владельца лягут в ту же очередь, что и телеграмные. */
    @Test
    void accountBecomesAJobOwner() {
        AccountService.Account account = accounts.register(EMAIL, PASSWORD, "Аня");

        assertThat(account.asOwner().type()).isEqualTo(Bot.owner.Owner.OwnerType.ACCOUNT);
        assertThat(account.asOwner().id()).isEqualTo(account.id().toString());
    }
}

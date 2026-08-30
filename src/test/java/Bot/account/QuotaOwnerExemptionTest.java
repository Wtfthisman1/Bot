package Bot.account;

/**
 * Владелец бота лимитом не ограничен: видеокарта его, электричество его.
 *
 * <p>Отдельный класс, а не случай в {@link QuotaServiceTest}: исключение
 * включается настройкой {@code admin.chat.id}, а её значение задаётся на весь
 * контекст.</p>
 */
import Bot.processing.RunningProcesses;
import Bot.owner.Owner;
import Bot.processing.JobRepository;
import Bot.processing.JobStore;
import Bot.processing.ProcessingJob;
import Bot.support.PostgresTestContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PostgresTestContainer.class, AccountService.class, QuotaService.class, JobStore.class,
        RunningProcesses.class})
@TestPropertySource(properties = {
        "quota.enabled=true",
        "quota.free-transcriptions-per-month=3",
        "quota.zone=Europe/Moscow",
        "admin.chat.id=REDACTED_CHAT_ID"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class QuotaOwnerExemptionTest {

    private static final long OWNER_CHAT = REDACTED_CHAT_IDL;
    private static final long SOMEONE_ELSE = 5150L;

    @Autowired QuotaService quotas;
    @Autowired AccountService accounts;
    @Autowired JobStore jobs;
    @Autowired JobRepository jobRepository;
    @Autowired AccountRepository accountRepository;

    @AfterEach
    void clean() {
        jobRepository.deleteAll();
        accountRepository.deleteAll();
    }

    @Test
    void ownerIsNotCounted() {
        Owner owner = Owner.telegram(OWNER_CHAT);

        for (int i = 0; i < 10; i++) {
            assertThat(quotas.allows(owner)).isTrue();
            jobs.enqueue(ProcessingJob.newLink(owner, "https://vimeo.com/" + i));
        }

        assertThat(quotas.forOwner(owner).unlimited()).isTrue();
        assertThat(quotas.forOwner(owner).describe()).isEqualTo("без ограничений");
    }

    /** Исключение переходит и на кабинет: человек один, а входов у него несколько. */
    @Test
    void theSiteAccountLinkedToThatChatIsFreeToo() {
        AccountService.Account site =
                accounts.register("hozyain@example.com", "очень-длинный-пароль", "Хозяин");
        String code = accounts.issueLinkCode(site.id());
        assertThat(accounts.redeemLinkCode(code, OWNER_CHAT)).isPresent();

        for (int i = 0; i < 5; i++) {
            jobs.enqueue(ProcessingJob.newLink(site.asOwner(), "https://vimeo.com/" + i));
        }

        assertThat(quotas.allows(site.asOwner())).isTrue();
        assertThat(quotas.of(site.id()).unlimited()).isTrue();
    }

    /** Всем остальным лимит остаётся: ради них он и заведён. */
    @Test
    void everyoneElseStillHasTheLimit() {
        Owner stranger = Owner.telegram(SOMEONE_ELSE);

        for (int i = 0; i < 3; i++) {
            assertThat(quotas.allows(stranger)).isTrue();
            jobs.enqueue(ProcessingJob.newLink(stranger, "https://vimeo.com/" + i));
        }

        assertThat(quotas.allows(stranger)).isFalse();
    }
}

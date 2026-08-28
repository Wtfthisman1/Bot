package Bot.account;

/**
 * Квота: три расшифровки в месяц на аккаунт, скачивания не в счёт, а
 * привязанная переписка тратит тот же лимит, что и сайт.
 */
import Bot.processing.RunningProcesses;
import Bot.owner.Owner;
import Bot.processing.JobRepository;
import Bot.processing.JobStore;
import Bot.processing.MediaKind;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PostgresTestContainer.class, AccountService.class, QuotaService.class, JobStore.class,
        RunningProcesses.class})
@TestPropertySource(properties = {
        "quota.enabled=true",
        "quota.free-transcriptions-per-month=3",
        "quota.zone=Europe/Moscow"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class QuotaServiceTest {

    private static final long CHAT_ID = 5150L;

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
    void fourthTranscriptionIsRefused() {
        Owner owner = account();

        for (int i = 0; i < 3; i++) {
            assertThat(quotas.allows(owner)).isTrue();
            jobs.enqueue(ProcessingJob.newLink(owner, "https://vimeo.com/" + i));
        }

        assertThat(quotas.allows(owner)).isFalse();
    }

    /** Видеокарта на скачивании не работает — и лимит оно не тратит. */
    @Test
    void downloadsDoNotCount() {
        Owner owner = account();

        for (int i = 0; i < 5; i++) {
            jobs.enqueue(ProcessingJob.newDownload(owner, "https://vimeo.com/" + i,
                    UUID.randomUUID().toString(), MediaKind.VIDEO));
        }

        assertThat(quotas.allows(owner)).isTrue();
        assertThat(quotas.forOwner(owner).used()).isZero();
    }

    /**
     * Уйти в бота и получить ещё три штуки нельзя: после привязки чат и сайт —
     * один аккаунт с одним счётчиком.
     */
    @Test
    void linkedChatSharesTheSameLimit() {
        AccountService.Account created = accounts.register("anya@example.com", "очень-длинный-пароль", "Аня");
        Owner site = created.asOwner();
        Owner chat = Owner.telegram(CHAT_ID);

        String code = accounts.issueLinkCode(created.id());
        assertThat(accounts.redeemLinkCode(code, CHAT_ID)).isPresent();

        jobs.enqueue(ProcessingJob.newLink(site, "https://vimeo.com/1"));
        jobs.enqueue(ProcessingJob.newLink(chat, "https://vimeo.com/2"));
        jobs.enqueue(ProcessingJob.newLink(chat, "https://vimeo.com/3"));

        assertThat(quotas.of(created.id()).used()).isEqualTo(3);
        assertThat(quotas.allows(site)).isFalse();
        assertThat(quotas.allows(chat)).isFalse();
    }

    /** Непривязанная переписка — тоже аккаунт: он заводится сам при первом счёте. */
    @Test
    void plainChatGetsItsOwnAccount() {
        Owner chat = Owner.telegram(777L);

        assertThat(quotas.allows(chat)).isTrue();

        assertThat(accountRepository.count()).isEqualTo(1);
    }

    private Owner account() {
        return accounts.register("kto@example.com", "очень-длинный-пароль", "Кто").asOwner();
    }
}

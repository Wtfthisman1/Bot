package Bot.processing;

/**
 * Проверка и постановка задачи — одним неделимым действием.
 *
 * <p>Тесты идут на настоящем Postgres: замок на строке аккаунта — это
 * {@code SELECT … FOR UPDATE}, и на подменной базе его не проверить.</p>
 */
import Bot.account.AccountRepository;
import Bot.account.AccountService;
import Bot.account.QuotaService;
import Bot.owner.Owner;
import Bot.service.StorageManager;
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

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PostgresTestContainer.class, AccountService.class, QuotaService.class, JobStore.class,
        RunningProcesses.class, StorageManager.class, JobAdmission.class})
@TestPropertySource(properties = {
        "quota.enabled=true",
        "quota.free-transcriptions-per-month=3",
        "quota.zone=Europe/Moscow",
        "app.storage.base=${java.io.tmpdir}/transcribot-admission-test"
})
// Каждый вызов идёт в своей транзакции, как в бою: общая транзакция теста
// скрыла бы ровно то, что проверяется, — блокировку строки аккаунта
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class JobAdmissionTest {

    private static final String URL = "https://vimeo.com/76979871";

    @Autowired JobAdmission admission;
    @Autowired AccountService accounts;
    @Autowired JobRepository jobs;
    @Autowired AccountRepository accountRepository;
    @Autowired PlatformTransactionManager transactions;

    @AfterEach
    void clean() {
        jobs.deleteAll();
        accountRepository.deleteAll();
    }

    @Test
    void acceptedJobLandsInTheQueue() {
        Owner owner = account();

        assertThat(admission.admit(ProcessingJob.newLink(owner, URL)).accepted()).isTrue();

        assertThat(jobs.count()).isEqualTo(1);
    }

    @Test
    void fourthTranscriptionIsRefusedAndNotQueued() {
        Owner owner = account();

        for (int i = 0; i < 3; i++) {
            assertThat(admission.admit(ProcessingJob.newLink(owner, URL + i)).accepted()).isTrue();
        }

        assertThat(admission.admit(ProcessingJob.newLink(owner, URL)))
                .isEqualTo(JobAdmission.Verdict.QUOTA_EXCEEDED);
        assertThat(jobs.count()).isEqualTo(3);
    }

    /**
     * Та самая гонка, ради которой всё это и делалось.
     *
     * <p>Раньше «посчитал» и «поставил» были двумя вызовами, и между ними
     * помещался соседний запрос: два одновременных нажатия на последней
     * доступной расшифровке читали «использовано 2 из 3» оба и оба ставили
     * задачу. Лимит в три штуки давал четыре.</p>
     *
     * <p>Проверяется не «запустим два потока и посмотрим»: два потока почти
     * всегда успевают разойтись сами, и такой тест проходит и без замка.
     * Здесь замок удерживается чужой транзакцией нарочно — и постановка
     * задачи обязана ждать, а дождавшись, увидеть уже занятое место.</p>
     */
    @Test
    void admissionWaitsWhileTheAccountIsBusy() throws Exception {
        Owner owner = account();
        for (int i = 0; i < 3; i++) {
            admission.admit(ProcessingJob.newLink(owner, URL + i));
        }
        assertThat(jobs.count()).isEqualTo(3);

        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // Чужая транзакция занимает строку аккаунта и не отпускает
            Future<?> holder = pool.submit(() -> new TransactionTemplate(transactions)
                    .executeWithoutResult(status -> {
                        accounts.lockForAdmission(owner);
                        locked.countDown();
                        try {
                            release.await(30, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }));
            assertThat(locked.await(30, TimeUnit.SECONDS)).isTrue();

            Future<JobAdmission.Verdict> waiting =
                    pool.submit(() -> admission.admit(ProcessingJob.newLink(owner, URL)));

            // Пока замок чужой, ответа нет вовсе — в этом и смысл
            assertThatThrownBy(() -> waiting.get(700, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);

            release.countDown();
            holder.get(30, TimeUnit.SECONDS);

            assertThat(waiting.get(30, TimeUnit.SECONDS))
                    .isEqualTo(JobAdmission.Verdict.QUOTA_EXCEEDED);
            assertThat(jobs.count()).isEqualTo(3);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    /** Аккаунт сайта: владелец задач — он сам. */
    private Owner account() {
        UUID id = accounts.register("admission" + UUID.randomUUID() + "@example.com",
                "длинная фраза для теста", "Тест").id();
        return Owner.account(id.toString());
    }
}

package Bot.processing;

/**
 * Очередь в базе: задача переживает перезапуск, и одну задачу не могут взять
 * двое. Тесты идут на настоящем Postgres — {@code FOR UPDATE SKIP LOCKED}
 * на подменной базе не проверить.
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

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PostgresTestContainer.class, JobStore.class})
// Каждый вызов store должен идти в своей транзакции, как в бою: общая
// транзакция теста скрыла бы ровно то, что мы проверяем — блокировки строк
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class JobStoreTest {

    private static final Owner OWNER = Owner.telegram(42L);
    private static final String URL = "https://youtu.be/dQw4w9WgXcQ";

    @Autowired JobStore store;
    @Autowired JobRepository repository;

    @AfterEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    void queuedJobIsHandedOutOnce() {
        ProcessingJob job = store.enqueue(ProcessingJob.newLink(OWNER, URL));

        Optional<ProcessingJob> claimed = store.claim();

        assertThat(claimed).map(ProcessingJob::id).contains(job.id());
        assertThat(store.claim()).isEmpty();
    }

    @Test
    void claimMarksJobRunningAndCountsAttempt() {
        ProcessingJob job = store.enqueue(ProcessingJob.newLink(OWNER, URL));

        store.claim();

        JobEntity stored = repository.findById(job.id()).orElseThrow();
        assertThat(stored.getState()).isEqualTo(JobState.RUNNING);
        assertThat(stored.getAttempts()).isEqualTo(1);
        assertThat(stored.getStartedAt()).isNotNull();
    }

    /** Ради этого очередь и переехала в базу. */
    @Test
    void jobInterruptedByRestartGoesBackToQueue() {
        ProcessingJob job = store.enqueue(ProcessingJob.newLink(OWNER, URL));
        store.claim();                       // воркер взял задачу и «умер»

        assertThat(store.recoverStuck()).isEqualTo(1);

        assertThat(store.claim()).map(ProcessingJob::id).contains(job.id());
        assertThat(repository.findById(job.id()).orElseThrow().getAttempts()).isEqualTo(2);
    }

    @Test
    void finishedJobsAreNotRecovered() {
        store.enqueue(ProcessingJob.newLink(OWNER, URL));
        ProcessingJob claimed = store.claim().orElseThrow();
        store.complete(claimed.id(), Path.of("/tmp/расшифровка.txt"));

        assertThat(store.recoverStuck()).isZero();
    }

    /** Скачивание закончилось — та же строка, а не вторая запись в истории. */
    @Test
    void downloadedJobReturnsToQueueForTranscription() {
        ProcessingJob job = store.enqueue(ProcessingJob.newLink(OWNER, URL));
        ProcessingJob claimed = store.claim().orElseThrow();

        store.moveToTranscribe(claimed.withFile(Path.of("/tmp/видео.mp4")));

        assertThat(repository.count()).isEqualTo(1);
        ProcessingJob again = store.claim().orElseThrow();
        assertThat(again.id()).isEqualTo(job.id());
        assertThat(again.stage()).isEqualTo(ProcessingJob.Stage.TRANSCRIBE);
        assertThat(again.filePath()).isEqualTo(Path.of("/tmp/видео.mp4"));
    }

    @Test
    void completedTranscriptionKeepsPathToResult() {
        store.enqueue(ProcessingJob.newFile(OWNER, Path.of("/tmp/видео.mp4")));
        ProcessingJob claimed = store.claim().orElseThrow();

        store.complete(claimed.id(), Path.of("/tmp/расшифровка.txt"));

        JobEntity stored = repository.findById(claimed.id()).orElseThrow();
        assertThat(stored.getState()).isEqualTo(JobState.DONE);
        assertThat(stored.getTranscriptPath()).isEqualTo("/tmp/расшифровка.txt");
        assertThat(stored.getFinishedAt()).isNotNull();
    }

    @Test
    void failureKeepsTheReason() {
        store.enqueue(ProcessingJob.newLink(OWNER, URL));
        ProcessingJob claimed = store.claim().orElseThrow();

        store.fail(claimed.id(), "❌ видео приватное");

        JobEntity stored = repository.findById(claimed.id()).orElseThrow();
        assertThat(stored.getState()).isEqualTo(JobState.FAILED);
        assertThat(stored.getError()).isEqualTo("❌ видео приватное");
    }

    @Test
    void statusCountsQueuedAndRunningSeparately() {
        store.enqueue(ProcessingJob.newLink(OWNER, URL));
        store.enqueue(ProcessingJob.newLink(OWNER, URL));
        store.enqueue(ProcessingJob.newLink(Owner.telegram(999L), URL));
        store.claim();

        JobStore.OwnerLoad load = store.load(OWNER);

        assertThat(load.running()).isEqualTo(1);
        assertThat(load.queued()).isEqualTo(1);
        assertThat(load.total()).isEqualTo(2);
    }

    /**
     * Воркеров несколько, и они ходят за задачами одновременно. Одна задача
     * двоим достаться не должна — на этом держится вся очередь.
     */
    @Test
    void concurrentWorkersNeverGetTheSameJob() throws Exception {
        int jobCount = 8;
        for (int i = 0; i < jobCount; i++) {
            store.enqueue(ProcessingJob.newLink(OWNER, URL + "/" + i));
        }

        int workers = 4;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        try {
            List<Callable<List<ProcessingJob>>> tasks = java.util.Collections.nCopies(workers,
                    () -> {
                        List<ProcessingJob> mine = new java.util.ArrayList<>();
                        Optional<ProcessingJob> job;
                        while ((job = store.claim()).isPresent()) {
                            mine.add(job.get());
                        }
                        return mine;
                    });

            List<ProcessingJob> claimed = new java.util.ArrayList<>();
            for (Future<List<ProcessingJob>> future : pool.invokeAll(tasks, 60, TimeUnit.SECONDS)) {
                claimed.addAll(future.get());
            }

            Set<java.util.UUID> unique = claimed.stream()
                    .map(ProcessingJob::id)
                    .collect(Collectors.toSet());
            assertThat(claimed).hasSize(jobCount);
            assertThat(unique).hasSize(jobCount);
        } finally {
            pool.shutdownNow();
        }
    }
}

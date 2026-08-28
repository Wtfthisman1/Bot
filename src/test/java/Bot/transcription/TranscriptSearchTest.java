package Bot.transcription;

/**
 * Поиск ищет место в записи: реплику со временем — и только среди своих задач.
 */
import Bot.processing.RunningProcesses;
import Bot.owner.Owner;
import Bot.processing.JobRepository;
import Bot.processing.JobStore;
import Bot.processing.ProcessingJob;
import Bot.support.PostgresTestContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PostgresTestContainer.class, TranscriptSegments.class, TranscriptSearch.class,
        JobStore.class, RunningProcesses.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TranscriptSearchTest {

    @Autowired TranscriptSearch search;
    @Autowired TranscriptSegments segments;
    @Autowired JobStore jobs;
    @Autowired JobRepository jobRepository;
    @Autowired TranscriptSegmentRepository segmentRepository;

    @TempDir Path dir;

    @AfterEach
    void clean() {
        segmentRepository.deleteAll();
        jobRepository.deleteAll();
    }

    /** Слово ищется в любой форме: это и есть смысл русской конфигурации. */
    @Test
    void findsWordInAnotherForm() throws IOException {
        UUID jobId = withSegments("""
                {"segments":[
                  {"start":0.0,"end":3.0,"text":" Речь пойдёт о гиппокампе и памяти."},
                  {"start":3.0,"end":6.0,"text":" А теперь о совершенно другом."}
                ]}""");

        List<TranscriptSearch.Hit> hits = search.find(List.of(jobId), "гиппокамп");

        assertThat(hits).singleElement().satisfies(hit -> {
            assertThat(hit.jobId()).isEqualTo(jobId);
            assertThat(hit.startMs()).isZero();
            assertThat(hit.at()).isEqualTo("0:00");
        });
    }

    /** Найденное слово приходит отдельным куском — шаблон его выделит. */
    @Test
    void matchIsMarkedInsideTheQuote() throws IOException {
        UUID jobId = withSegments("""
                {"segments":[{"start":12.0,"end":15.0,"text":" Здесь про гиппокамп."}]}""");

        TranscriptSearch.Hit hit = search.find(List.of(jobId), "гиппокамп").get(0);

        assertThat(hit.parts()).anyMatch(TranscriptSearch.Part::hit);
        assertThat(hit.parts()).filteredOn(TranscriptSearch.Part::hit)
                .extracting(TranscriptSearch.Part::text)
                .containsExactly("гиппокамп");
        assertThat(hit.at()).isEqualTo("0:12");
    }

    /** Чужая задача не ищется: список задач приходит снаружи и уже проверен. */
    @Test
    void otherJobsAreNotSearched() throws IOException {
        UUID mine = withSegments("""
                {"segments":[{"start":0.0,"end":1.0,"text":" Про гиппокамп."}]}""");
        UUID other = withSegments("""
                {"segments":[{"start":0.0,"end":1.0,"text":" Тоже про гиппокамп."}]}""");

        assertThat(search.find(List.of(mine), "гиппокамп"))
                .extracting(TranscriptSearch.Hit::jobId).containsExactly(mine);
        assertThat(other).isNotEqualTo(mine);
    }

    @Test
    void emptyQueryFindsNothing() throws IOException {
        UUID jobId = withSegments("""
                {"segments":[{"start":0.0,"end":1.0,"text":" Что-нибудь."}]}""");

        assertThat(search.find(List.of(jobId), "   ")).isEmpty();
        assertThat(search.find(List.of(jobId), null)).isEmpty();
    }

    /* ───────── helpers ───────── */

    private UUID withSegments(String markup) throws IOException {
        UUID jobId = jobs.enqueue(
                ProcessingJob.newLink(Owner.telegram(42L), "https://vimeo.com/" + UUID.randomUUID())).id();

        Path txt = dir.resolve(jobId + ".txt");
        Files.writeString(txt, "текст");
        Files.writeString(dir.resolve(jobId + ".json"), markup);
        segments.importFrom(jobId, txt);
        return jobId;
    }
}

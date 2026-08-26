package Bot.transcription;

/**
 * Разметка Whisper превращается в сегменты в базе — с говорящими и без них.
 */
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
@Import({PostgresTestContainer.class, TranscriptSegments.class, JobStore.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TranscriptSegmentsTest {

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

    @Test
    void markupBecomesSegments() throws IOException {
        UUID jobId = job();
        Path txt = transcript("""
                {"language":"ru","segments":[
                  {"start":0.0,"end":2.5,"text":" Привет."},
                  {"start":2.5,"end":4.25,"text":" И тебе привет."}
                ]}""");

        assertThat(segments.importFrom(jobId, txt)).isEqualTo(2);

        List<TranscriptSegmentEntity> saved = segments.of(jobId).segments();
        assertThat(saved).extracting(TranscriptSegmentEntity::getText)
                .containsExactly("Привет.", "И тебе привет.");
        assertThat(saved).extracting(TranscriptSegmentEntity::getOrd)
                .containsExactly(0, 1);
        assertThat(saved.get(1).getStartMs()).isEqualTo(2500);
        assertThat(saved.get(1).getEndMs()).isEqualTo(4250);
        assertThat(saved.get(0).getSpeaker()).isNull();
    }

    /** Диаризация дописывает метку в ту же разметку — она должна доехать до базы. */
    @Test
    void speakersSurvive() throws IOException {
        UUID jobId = job();
        Path txt = transcript("""
                {"language":"ru","segments":[
                  {"start":0.0,"end":1.0,"text":" Раз.","speaker":"SPEAKER_00"},
                  {"start":1.0,"end":2.0,"text":" Два.","speaker":"SPEAKER_01"}
                ]}""");

        segments.importFrom(jobId, txt);

        assertThat(segments.of(jobId).segments()).extracting(TranscriptSegmentEntity::getSpeaker)
                .containsExactly("SPEAKER_00", "SPEAKER_01");
    }

    /**
     * Пустые куски Whisper выдаёт на паузах и музыке: в плеере это пустые
     * строки, а искать по ним нечего.
     */
    @Test
    void emptySegmentsAreDropped() throws IOException {
        UUID jobId = job();
        Path txt = transcript("""
                {"segments":[
                  {"start":0.0,"end":1.0,"text":"   "},
                  {"start":1.0,"end":2.0,"text":" Есть текст."}
                ]}""");

        assertThat(segments.importFrom(jobId, txt)).isEqualTo(1);
        assertThat(segments.of(jobId).segments()).singleElement()
                .extracting(TranscriptSegmentEntity::getOrd).isEqualTo(0);
    }

    /** Повтор разбора не должен спотыкаться о прежние сегменты той же задачи. */
    @Test
    void secondImportReplacesTheFirst() throws IOException {
        UUID jobId = job();
        Path txt = transcript("""
                {"segments":[{"start":0.0,"end":1.0,"text":" Было."}]}""");
        segments.importFrom(jobId, txt);

        Files.writeString(txt.resolveSibling("t.json"), """
                {"segments":[{"start":0.0,"end":1.0,"text":" Стало."}]}""");
        segments.importFrom(jobId, txt);

        assertThat(segments.of(jobId).segments()).singleElement()
                .extracting(TranscriptSegmentEntity::getText).isEqualTo("Стало.");
    }

    /** Разметки может не быть вовсе — старые задачи и чужие файлы. */
    @Test
    void missingMarkupIsNotAnError() throws IOException {
        UUID jobId = job();
        Path txt = dir.resolve("t.txt");
        Files.writeString(txt, "Просто текст");

        assertThat(segments.importFrom(jobId, txt)).isZero();
        assertThat(segments.of(jobId).segments()).isEmpty();
    }

    /* ───────── helpers ───────── */

    private UUID job() {
        return jobs.enqueue(ProcessingJob.newLink(Owner.telegram(77L), "https://vimeo.com/1")).id();
    }

    /** Кладёт .txt и разметку рядом — ровно так, как их оставляет Whisper. */
    private Path transcript(String markup) throws IOException {
        Path txt = dir.resolve("t.txt");
        Files.writeString(txt, "текст");
        Files.writeString(dir.resolve("t.json"), markup);
        return txt;
    }
}

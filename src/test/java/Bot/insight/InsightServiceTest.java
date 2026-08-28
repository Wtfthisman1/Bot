package Bot.insight;

/**
 * Заказы на обработку и сам счёт — на подставной модели.
 *
 * <p>Модель здесь фальшивая намеренно: проверяется не качество пересказа, а
 * сборка вокруг него — сколько раз мы ходим к модели, что ей отдаём и что
 * делаем с ответом. На живой модели те же проверки заняли бы видеокарту на
 * минуты и зависели бы от её настроения.</p>
 */
import Bot.owner.Owner;
import Bot.processing.JobRepository;
import Bot.processing.JobState;
import Bot.processing.JobStore;
import Bot.processing.ProcessingJob;
import Bot.support.PostgresTestContainer;
import Bot.transcription.TranscriptSegmentEntity;
import Bot.transcription.TranscriptSegmentRepository;
import Bot.transcription.TranscriptSegments;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PostgresTestContainer.class, TranscriptSegments.class, JobStore.class,
        InsightService.class, InsightServiceTest.FakeModel.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
// Кусок в 200 символов — это несколько реплик: так в тесте видно оба пути,
// и «влезло за один раз», и «пришлось резать и сводить»
@TestPropertySource(properties = "insight.chunk-chars=200")
class InsightServiceTest {

    @Autowired InsightService insights;
    @Autowired InsightRepository insightRepository;
    @Autowired TranscriptSegmentRepository segmentRepository;
    @Autowired JobStore jobs;
    @Autowired JobRepository jobRepository;
    @Autowired LanguageModel model;

    private Fake fake;
    private UUID jobId;

    @BeforeEach
    void setUp() {
        fake = (Fake) model;
        fake.reset();
        jobId = jobs.enqueue(ProcessingJob.newLink(Owner.telegram(77L), "https://vimeo.com/1")).id();
    }

    @AfterEach
    void clean() {
        insightRepository.deleteAll();
        segmentRepository.deleteAll();
        jobRepository.deleteAll();
    }

    @Test
    void shortTranscriptIsRetoldInOneQuestion() {
        transcript("Раз.", "Два.", "Три.");

        String text = insights.compute(
                new InsightService.Order(1, jobId, InsightKind.SUMMARY, 15, null, null));

        assertThat(fake.prompts).hasSize(1);
        assertThat(fake.prompts.get(0)).contains("[0:00] Раз.").contains("[0:20] Три.");
        assertThat(text).isEqualTo("ответ 1");
    }

    /** Час записи в окно не влезает: куски пересказываются порознь и сводятся. */
    @Test
    void longTranscriptIsAskedInPiecesAndThenPutTogether() {
        transcript(longReplies(12));

        insights.compute(new InsightService.Order(1, jobId, InsightKind.SUMMARY, 15, null, null));

        // Последний вопрос — сведение: в нём уже не расшифровка, а ответы по кускам
        assertThat(fake.prompts).hasSizeGreaterThan(2);
        String last = fake.prompts.get(fake.prompts.size() - 1);
        assertThat(last).contains("Собери из них цельное изложение").contains("ответ 1");
    }

    /** Доля от текста называется модели словами: проценты ей ничего не говорят. */
    @Test
    void theAskedLengthIsSpelledOutInWords() {
        transcript("Раз два три четыре пять.");   // половина от пяти слов — это не пересказ

        insights.compute(
                new InsightService.Order(1, jobId, InsightKind.SUMMARY, 50, null, null));

        assertThat(fake.prompts.get(0)).contains("60 слов");   // ниже этого не опускаемся
    }

    @Test
    void topicFoundInOnePieceIsReturnedAsIs() {
        transcript(longReplies(12));
        fake.answers = prompt -> prompt.contains("идёт с 0:00")
                ? "Про это говорят в [0:00]." : "НЕТ";

        String text = insights.compute(
                new InsightService.Order(1, jobId, InsightKind.TOPIC, null, "сроки", null));

        assertThat(text).isEqualTo("Про это говорят в [0:00].");
    }

    /** Тему могли не обсуждать вовсе — и это ответ, а не ошибка. */
    @Test
    void topicMissingFromTheRecordingIsSaidPlainly() {
        transcript("Раз.", "Два.");
        fake.answers = prompt -> "НЕТ.";

        String text = insights.compute(
                new InsightService.Order(1, jobId, InsightKind.TOPIC, null, "сроки", null));

        assertThat(text).isEqualTo("Про «сроки» в этой записи ничего не нашлось.");
    }

    @Test
    void secondOrderWaitsForTheFirst() {
        transcript("Раз.", "Два.");

        assertThat(insights.order(jobId, InsightKind.SUMMARY, 15, null)).isEmpty();
        assertThat(insights.order(jobId, InsightKind.SUMMARY, 30, null))
                .get().asString().contains("ещё считается");
    }

    @Test
    void thereIsNothingToOrderWithoutMarkup() {
        assertThat(insights.order(jobId, InsightKind.SUMMARY, 15, null))
                .get().asString().contains("нет разметки");
    }

    @Test
    void orderIsRefusedWhenTheModelIsSilent() {
        transcript("Раз.", "Два.");
        fake.available = false;

        assertThat(insights.order(jobId, InsightKind.SUMMARY, 15, null))
                .get().asString().contains("недоступна");
    }

    /** Заказ без темы — это пустой вопрос к модели, а не «расскажи что-нибудь». */
    @Test
    void topicOrderNeedsAQuestion() {
        transcript("Раз.", "Два.");

        assertThat(insights.order(jobId, InsightKind.TOPIC, null, "   "))
                .get().asString().contains("что искать");
    }

    /** Тема идёт в подсказку к каждому куску: длинная съедает окно модели. */
    @Test
    void tooLongTopicIsRefused() {
        transcript("Раз.", "Два.");

        assertThat(insights.order(jobId, InsightKind.TOPIC, null, "с".repeat(201)))
                .get().asString().contains("слишком длинная");
    }

    @Test
    void claimedOrderIsTakenOnlyOnce() {
        transcript("Раз.", "Два.");
        insights.order(jobId, InsightKind.SUMMARY, 15, null);

        Optional<InsightService.Order> first = insights.claim();
        Optional<InsightService.Order> second = insights.claim();

        assertThat(first).isPresent();
        assertThat(second).isEmpty();
        assertThat(insightRepository.findById(first.get().id()))
                .get().extracting(InsightEntity::getState).isEqualTo(JobState.RUNNING);
    }

    /**
     * Заказ из чата помнит, куда отвечать: страницы, которая показала бы
     * посчитанное, у Telegram нет.
     */
    @Test
    void orderFromChatRemembersWhereToAnswer() {
        transcript("Раз.", "Два.");
        insights.order(jobId, InsightKind.SUMMARY, null, null, 77L);

        assertThat(insights.claim()).get()
                .extracting(InsightService.Order::notifyChatId).isEqualTo(77L);
    }

    /** Заказ со страницы в чат не уходит: там его показывать некому и незачем. */
    @Test
    void orderFromPageHasNoChat() {
        transcript("Раз.", "Два.");
        insights.order(jobId, InsightKind.SUMMARY, 15, null);

        assertThat(insights.claim()).get()
                .extracting(InsightService.Order::notifyChatId).isNull();
    }

    /** Прерванное перезапуском возвращается в очередь: тот воркер уже не вернётся. */
    @Test
    void interruptedOrderGoesBackToTheQueue() {
        transcript("Раз.", "Два.");
        insights.order(jobId, InsightKind.SUMMARY, 15, null);
        long id = insights.claim().orElseThrow().id();

        insights.recoverStuck();

        assertThat(insightRepository.findById(id))
                .get().extracting(InsightEntity::getState).isEqualTo(JobState.QUEUED);
    }

    /* ───────── helpers ───────── */

    private void transcript(String... texts) {
        List<TranscriptSegmentEntity> segments = new ArrayList<>();
        for (int i = 0; i < texts.length; i++) {
            TranscriptSegmentEntity segment = new TranscriptSegmentEntity();
            segment.setJobId(jobId);
            segment.setOrd(i);
            segment.setStartMs(i * 10_000);
            segment.setEndMs(i * 10_000 + 9_000);
            segment.setText(texts[i]);
            segments.add(segment);
        }
        segmentRepository.saveAll(segments);
    }

    private static String[] longReplies(int count) {
        String[] texts = new String[count];
        for (int i = 0; i < count; i++) {
            texts[i] = "Реплика номер " + i + ", в ней десяток слов и немного смысла для веса.";
        }
        return texts;
    }

    /** Подставная модель: отвечает по порядку и запоминает, о чём её спросили. */
    static class Fake implements LanguageModel {

        final List<String> prompts = new ArrayList<>();
        java.util.function.Function<String, String> answers;
        boolean available = true;

        void reset() {
            prompts.clear();
            answers = null;
            available = true;
        }

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public String name() {
            return "подставная";
        }

        @Override
        public String ask(String instruction, String prompt) {
            prompts.add(prompt);
            return answers != null ? answers.apply(prompt) : "ответ " + prompts.size();
        }

        @Override
        public void unload() {
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeModel {

        @Bean
        LanguageModel languageModel() {
            return new Fake();
        }
    }
}

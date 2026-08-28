package Bot.insight;

/**
 * Обработка расшифровки языковой моделью: заказы и сам счёт.
 *
 * <p>Ответственность: принять заказ — со страницы или из чата, — сложить его в
 * очередь и, когда до него дойдут руки у {@link InsightWorker}, сходить в
 * модель нужное число раз и вернуть текст. Проверка «моя ли это задача» сюда не
 * заходит: она живёт там же, где для остального кабинета, — в
 * {@code JobHistory}, а для чата в {@code LocalHomeApi}.</p>
 *
 * <p>Час записи в окно модели не влезает, поэтому счёт идёт в два прохода:
 * сначала по кускам ({@link TranscriptChunks}), потом сведение. Это дороже
 * одного запроса, но единственная альтернатива — молча пересказать хвост
 * записи и выдать это за пересказ целого.</p>
 *
 * <p>Модель считает по сегментам из базы, а не по файлу Whisper: человек уже
 * мог поправить распознанное в редакторе, и пересказывать ему при этом
 * неисправленное — значит объяснять, почему в выжимке снова «гиппокамб».</p>
 */
import Bot.config.Profiles;
import Bot.insight.TranscriptChunks.Chunk;
import Bot.processing.JobState;
import Bot.transcription.Transcript;
import Bot.transcription.TranscriptSegments;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Profile(Profiles.HOME)
@Service
@RequiredArgsConstructor
@Slf4j
public class InsightService {

    /** Границы «насколько ужать»: короче — пересказ ни о чём, длиннее — не выжимка. */
    public static final int MIN_RATIO = 5;
    public static final int MAX_RATIO = 50;

    /** Ниже этого выжимка перестаёт быть связным текстом даже у короткой записи. */
    private static final int MIN_WORDS = 60;

    private static final List<JobState> PENDING = List.of(JobState.QUEUED, JobState.RUNNING);

    /**
     * Общие правила для всех запросов.
     *
     * <p>Главное здесь — запрет добавлять от себя. Модель, которую попросили
     * пересказать разговор, охотно дополняет его тем, что «обычно говорят» на
     * эту тему, и отличить дополненное от сказанного человек уже не может.</p>
     */
    private static final String INSTRUCTION = """
            Ты работаешь с расшифровками записей: встреч, лекций, интервью, видео.
            Правила, одинаковые для всех ответов:
            1. Опирайся только на текст расшифровки. Не добавляй ничего от себя,
               даже если знаешь тему лучше говорящих.
            2. Если в тексте чего-то нет — так и скажи, не догадывайся.
            3. Не переписывай реплики по одной и не воспроизводи диалог.
               Излагай своими словами.
            4. Имена говорящих в пунктах не повторяй.
            5. Время пиши в квадратных скобках, ровно как в расшифровке: [0:12].
               Не выдумывай времени, которого в тексте нет.
            6. Отвечай на том языке, на котором говорят в расшифровке.
            7. Пиши обычным текстом, без markdown-разметки.
            """;

    private final InsightRepository insights;
    private final TranscriptSegments transcripts;
    private final LanguageModel model;

    /**
     * Сколько символов расшифровки уходит модели за один раз.
     *
     * <p>Меньше окна не просто так: в окно кроме куска должны поместиться
     * правила, вопрос и сам ответ. Значение по умолчанию посчитано под окно
     * в 8192 токена — это примерно 15 минут разговора.</p>
     */
    @Value("${insight.chunk-chars:6000}")
    private int chunkChars;

    /* ───────── заказы ───────── */

    /** Что уже посчитано и что считается по этой задаче — для страницы. */
    @Transactional(readOnly = true)
    public List<InsightEntity> of(UUID jobId) {
        return insights.findByJobIdOrderByCreatedAtDesc(jobId);
    }

    /** Готова ли модель отвечать: по этому страница решает, показывать ли кнопки. */
    public boolean ready() {
        return model.available();
    }

    /**
     * Освобождает видеопамять после счёта.
     *
     * <p>Вызывает воркер, когда обработка закончена или сорвалась: держать веса
     * в памяти между заказами нельзя — там же считается Whisper.</p>
     */
    public void unloadModel() {
        model.unload();
    }

    /**
     * Ставит обработку в очередь; результат покажет страница.
     *
     * @return сообщение для человека; пусто — заказ принят
     */
    @Transactional
    public Optional<String> order(UUID jobId, InsightKind kind, Integer ratio, String topic) {
        return order(jobId, kind, ratio, topic, null);
    }

    /**
     * Ставит обработку в очередь и запоминает, куда прислать готовое.
     *
     * <p>{@code notifyChatId} задаётся, когда заказ пришёл из Telegram: там
     * страницы, которая сама покажет ответ, нет — ответ должен прийти в чат.
     * Пусто — обычный заказ со страницы.</p>
     *
     * @return сообщение для человека; пусто — заказ принят
     */
    @Transactional
    public Optional<String> order(UUID jobId, InsightKind kind, Integer ratio, String topic,
                                  Long notifyChatId) {
        if (!model.available()) {
            return Optional.of("Обработка текста сейчас недоступна: языковая модель не отвечает.");
        }
        if (transcripts.of(jobId).isEmpty()) {
            return Optional.of("У этой задачи нет разметки по времени — обрабатывать нечего.");
        }
        // Второй такой же заказ не ускорит первый, а видеокарту займёт вдвое:
        // очередь к ней одна на всех, включая расшифровки
        if (insights.existsByJobIdAndStateIn(jobId, PENDING)) {
            return Optional.of("Предыдущая обработка ещё считается — дождитесь её.");
        }

        String question = topic == null ? "" : topic.strip();
        if (kind == InsightKind.TOPIC && question.isEmpty()) {
            return Optional.of("Напишите, что искать в записи.");
        }

        InsightEntity insight = new InsightEntity();
        insight.setJobId(jobId);
        insight.setKind(kind);
        insight.setRatio(kind == InsightKind.SUMMARY ? clamp(ratio) : null);
        insight.setTopic(kind == InsightKind.TOPIC ? question : null);
        insight.setState(JobState.QUEUED);
        insight.setNotifyChatId(notifyChatId);
        insight.setCreatedAt(Instant.now());
        insights.save(insight);

        log.info("Заказана обработка расшифровки: jobId={}, вид={}, доля={}, тема='{}', чат={}",
                jobId, kind, insight.getRatio(), insight.getTopic(), notifyChatId);
        return Optional.empty();
    }

    /** Убирает обработку из списка. Чужую не тронет: задача уже сверена с аккаунтом. */
    @Transactional
    public void forget(UUID jobId, long id) {
        insights.findById(id)
                .filter(insight -> insight.getJobId().equals(jobId))
                .ifPresent(insights::delete);
    }

    /* ───────── очередь ───────── */

    /**
     * Забирает следующий заказ и помечает его считающимся.
     *
     * <p>Возвращается копия, а не сущность: считать её будут вне транзакции,
     * минутами, и держать всё это время открытую сессию базы незачем.</p>
     */
    @Transactional
    public Optional<Order> claim() {
        return insights.lockNextQueued()
                .flatMap(insights::findById)
                .map(insight -> {
                    insight.setState(JobState.RUNNING);
                    return new Order(insight.getId(), insight.getJobId(), insight.getKind(),
                            insight.getRatio(), insight.getTopic(), insight.getNotifyChatId());
                });
    }

    @Transactional
    public void complete(long id, String text) {
        insights.findById(id).ifPresent(insight -> {
            insight.setState(JobState.DONE);
            insight.setText(text);
            insight.setModel(model.name());
            insight.setFinishedAt(Instant.now());
        });
    }

    @Transactional
    public void fail(long id, String error) {
        insights.findById(id).ifPresent(insight -> {
            insight.setState(JobState.FAILED);
            insight.setError(error);
            insight.setFinishedAt(Instant.now());
        });
    }

    /** Оставшееся от прошлого запуска: тот воркер уже не вернётся. */
    @Transactional
    public void recoverStuck() {
        insights.findAll().stream()
                .filter(insight -> insight.getState() == JobState.RUNNING)
                .forEach(insight -> insight.setState(JobState.QUEUED));
    }

    /* ───────── счёт ───────── */

    /**
     * Считает обработку. Долго: минуты на каждый кусок записи.
     *
     * <p>Вызывается только воркером и только под пропуском на видеокарту.</p>
     */
    public String compute(Order order) {
        Transcript transcript = transcripts.of(order.jobId());
        if (transcript.isEmpty()) {
            throw new IllegalStateException("Разметка расшифровки пропала — обрабатывать нечего");
        }

        List<Chunk> chunks = TranscriptChunks.of(transcript, chunkChars);
        log.info("Обработка расшифровки: jobId={}, вид={}, кусков={}",
                order.jobId(), order.kind(), chunks.size());

        return switch (order.kind()) {
            case SUMMARY -> summary(chunks, TranscriptChunks.words(transcript), order.ratio());
            case TOPIC -> topic(chunks, order.topic());
        };
    }

    /**
     * Выжимка: пересказ на заданную долю от исходного текста.
     *
     * <p>Доля переводится в слова и называется модели прямо: «примерно 300
     * слов». Проценты ей ничего не говорят — исходного текста она целиком не
     * видела, куски приходили порознь.</p>
     */
    private String summary(List<Chunk> chunks, int words, int ratio) {
        int target = Math.max(MIN_WORDS, words * ratio / 100);

        if (chunks.size() == 1) {
            return model.ask(INSTRUCTION, """
                    Ниже расшифровка записи, она идёт с %s.
                    
                    Перескажи её: сначала абзац о том, чему запись посвящена, затем
                    главные мысли списком в том порядке, в каком они прозвучали. Каждую
                    мысль начинай с новой строки и с метки времени, где она прозвучала.
                    Метка одна и без диапазона: [2:14], а не [2:14-3:20]. Пройди запись
                    до конца: последние минуты так же важны, как первые.
                    
                    Изложение должно быть не короче %d слов — это важно, короткий ответ
                    не годится.
                    
                    %s""".formatted(chunks.get(0).range(), target, chunks.get(0).text()));
        }

        List<String> retold = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            Chunk chunk = chunks.get(i);
            log.info("Кусок {} из {} ({})", i + 1, chunks.size(), chunk.range());
            retold.add(model.ask(INSTRUCTION, """
                    Ниже кусок расшифровки записи, часть %d из %d. Он идёт с %s.
                    
                    Выдели главные темы этого куска — не больше шести. Каждую опиши
                    одним-двумя предложениями и начни с новой строки с метки времени,
                    где тема поднимается. Метка одна и без диапазона: [2:14], а не
                    [2:14-3:20]. Переспросы и мелкие уточнения включай в ближайшую тему,
                    а не выноси отдельно. Темы должны покрывать весь кусок до конца,
                    а не только его начало.
                    
                    %s""".formatted(i + 1, chunks.size(), chunk.range(), chunk.text())));
        }

        return model.ask(INSTRUCTION, """
                Ниже разборы кусков одной записи, по порядку.
                
                Собери из них цельное изложение: сначала абзац о том, чему запись
                посвящена, затем главные мысли списком. Каждую мысль начинай с новой
                строки и с метки времени; метка одна и без диапазона. Раскрывай мысль
                в два-три предложения, а не одной строкой. Повторы из разных кусков
                объедини.
                
                Изложение должно быть не короче %d слов — это важно, короткий ответ
                не годится.
                
                %s""".formatted(target, String.join("\n\n", retold)));
    }

    /**
     * Разбор по теме: всё, что об этом говорили, собранное в связный рассказ.
     *
     * <p>Куски перебираются все, а не только найденные поиском по словам: тему
     * обсуждают, ни разу её не назвав — «эта болезнь», «то самое исследование».
     * Полнотекстовый поиск такие места пропускает, и ради них эта кнопка и
     * заведена.</p>
     */
    private String topic(List<Chunk> chunks, String question) {
        List<String> found = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            Chunk chunk = chunks.get(i);
            log.info("Кусок {} из {} ({})", i + 1, chunks.size(), chunk.range());
            String answer = model.ask(INSTRUCTION, """
                    Ниже кусок расшифровки записи, он идёт с %s.
                    
                    Тема: «%s».
                    
                    Коротко перескажи, что в этом куске говорили именно по этой теме,
                    и каждое место начинай с новой строки с метки времени в квадратных
                    скобках. Метка одна и без диапазона: [2:14], а не [2:14-3:20].
                    Считается и то, где тему не называют прямо, но говорят именно о ней.
                    Не выписывай то, что относится к теме лишь отдалённо: сомневаешься —
                    пропусти. Реплики целиком не цитируй. Если по этой теме здесь нет
                    ничего — ответь одним словом: НЕТ.
                    
                    %s""".formatted(chunk.range(), question, chunk.text()));
            if (!isNothing(answer)) {
                found.add(answer);
            }
        }

        if (found.isEmpty()) {
            return "Про «%s» в этой записи ничего не нашлось.".formatted(question);
        }
        if (found.size() == 1) {
            // Сводить нечего: связность и так на месте, а лишний заход к модели —
            // ещё один повод ей что-нибудь присочинить
            return found.get(0);
        }

        return model.ask(INSTRUCTION, """
                Ниже выписки из одной записи по теме «%s», в том порядке, в каком они
                прозвучали.
                
                Сначала выброси всё, что к теме на самом деле не относится: в выписки
                могло попасть лишнее. Из оставшегося собери связный рассказ — что об
                этом говорили, как одно связано с другим, к чему в итоге пришли.
                Сохрани метки времени в квадратных скобках. Ничего не добавляй от себя.
                
                %s""".formatted(question, String.join("\n\n", found)));
    }

    /** «НЕТ», «нет.», «Нет, ничего» — всё это значит «в этом куске пусто». */
    private static boolean isNothing(String answer) {
        String clean = answer.strip().toLowerCase().replaceAll("[.!\\s]+$", "");
        return clean.isEmpty() || clean.equals("нет") || clean.equals("no");
    }

    private static int clamp(Integer ratio) {
        int value = ratio == null ? 15 : ratio;
        return Math.min(MAX_RATIO, Math.max(MIN_RATIO, value));
    }

    /**
     * Заказ, взятый в работу: всё нужное для счёта и для ответа, без открытой
     * сессии базы.
     *
     * <p>{@code notifyChatId} не пуст, если заказ пришёл из чата: воркер
     * отправит туда готовое сам. У заказа со страницы он пуст — там ответ
     * забирает сама страница.</p>
     */
    public record Order(long id, UUID jobId, InsightKind kind, Integer ratio, String topic,
                        Long notifyChatId) {}
}

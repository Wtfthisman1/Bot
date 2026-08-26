package Bot.transcription;

/**
 * Расшифровка как список сегментов, а не как файл.
 *
 * <p>Ответственность: перенести разметку, которую Whisper и без того кладёт
 * рядом с текстом в {@code .json}, в таблицу — и отдавать её тем, кому нужна
 * не простыня текста, а куски со временем: плееру, редактору и поиску.</p>
 *
 * <p>Почему разбор, а не чтение файла на каждый показ: файлы стираются через
 * две недели вместе с видео, а расшифровку человек правит и ищет по ней и
 * позже. Кроме того, по строкам в таблице умеет искать база, а по json — нет.</p>
 *
 * <p>Сбой разбора не роняет задачу: расшифровка уже готова и уже отправлена.
 * Без сегментов кабинет покажет её текстом — как показывал до сих пор.</p>
 */
import Bot.config.Profiles;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Profile(Profiles.HOME)
@Service
@RequiredArgsConstructor
@Slf4j
public class TranscriptSegments {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final TranscriptSegmentRepository segments;

    /**
     * Разбирает разметку Whisper и кладёт её в базу.
     *
     * @param txt путь к готовой расшифровке; {@code .json} лежит рядом под тем
     *            же именем — его пишет тот же проход Whisper
     * @return сколько сегментов сохранено; 0 — разметки не было или она пустая
     */
    @Transactional
    public int importFrom(UUID jobId, Path txt) {
        Path json = markupOf(txt);
        if (!Files.exists(json)) {
            log.info("Разметки рядом с расшифровкой нет, сегменты не сохранены: {}", json);
            return 0;
        }

        List<TranscriptSegmentEntity> parsed;
        try {
            parsed = parse(jobId, json);
        } catch (Exception e) {
            log.warn("Не разобрал разметку расшифровки: {}", json, e);
            return 0;
        }

        if (parsed.isEmpty()) {
            return 0;
        }

        // Задачу могли пересчитать: остатки прежней расшифровки столкнулись бы
        // с новой на уникальности (job_id, ord) уже посреди вставки
        segments.deleteByJobId(jobId);
        segments.saveAll(parsed);
        log.info("Сегментов расшифровки сохранено: {} (jobId={})", parsed.size(), jobId);
        return parsed.size();
    }

    /** Расшифровка задачи по порядку; пусто — значит, разметки нет. */
    @Transactional(readOnly = true)
    public List<TranscriptSegmentEntity> of(UUID jobId) {
        return segments.findByJobIdOrderByOrd(jobId);
    }

    /* ───────── разбор ───────── */

    private List<TranscriptSegmentEntity> parse(UUID jobId, Path json) throws Exception {
        Markup markup = JSON.readValue(Files.readString(json), Markup.class);
        List<TranscriptSegmentEntity> result = new ArrayList<>();
        if (markup.segments() == null) {
            return result;
        }

        int ord = 0;
        for (Segment segment : markup.segments()) {
            String text = segment.text() == null ? "" : segment.text().strip();
            // Пустые куски Whisper выдаёт на паузах и музыке: в плеере они
            // выглядят пустыми строками, а искать по ним нечего
            if (text.isEmpty()) {
                continue;
            }

            TranscriptSegmentEntity entity = new TranscriptSegmentEntity();
            entity.setJobId(jobId);
            entity.setOrd(ord++);
            entity.setStartMs(millis(segment.start()));
            entity.setEndMs(Math.max(millis(segment.end()), millis(segment.start())));
            entity.setSpeaker(blankToNull(segment.speaker()));
            entity.setText(text);
            result.add(entity);
        }
        return result;
    }

    /**
     * Разметка лежит рядом с текстом под тем же именем: {@code .txt} → {@code .json}.
     *
     * <p>Отдельного перечисления для неё нет намеренно: {@link TranscriptFormat} —
     * это то, что человек выбирает кнопкой, а json ему не нужен.</p>
     */
    private static Path markupOf(Path txt) {
        String name = txt.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        return txt.resolveSibling(stem + ".json");
    }

    private static int millis(Double seconds) {
        return seconds == null ? 0 : (int) Math.round(seconds * 1000);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /** Только те поля разметки, которые нам нужны: у Whisper их втрое больше. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Markup(String language, List<Segment> segments) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Segment(Double start, Double end, String text, String speaker) {}
}

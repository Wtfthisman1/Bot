package Bot.transcription;

/**
 * Поиск по расшифровкам одного человека.
 *
 * <p>Ответственность: найти не запись, а место в записи. Ответ — реплика с
 * временем, по которому страница расшифровки перематывает плеер.</p>
 *
 * <p>Ищет только среди задач, переданных вызывающей стороной: проверка «моё ли
 * это» живёт там же, где и для остальных страниц кабинета, и заводить второе
 * место, где можно ошибиться, незачем.</p>
 */
import Bot.config.Profiles;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Profile(Profiles.HOME)
@Service
@RequiredArgsConstructor
@Slf4j
public class TranscriptSearch {

    /** Больше человек всё равно не просматривает — он уточняет запрос. */
    private static final int LIMIT = 50;

    /**
     * Метки, которыми Postgres огораживает найденные слова.
     *
     * <p>Не {@code <b>}: подсветка возвращается разобранной на куски, а разметку
     * рисует шаблон. Иначе в страницу пришлось бы выводить сырой HTML из базы —
     * то есть однажды выпустить туда чужой тег. Взяты управляющие символы:
     * в расшифровке их не бывает, спутать их с текстом нельзя.</p>
     */
    private static final String OPEN = "\u0001";
    private static final String CLOSE = "\u0002";

    private final TranscriptSegmentRepository segments;

    @Transactional(readOnly = true)
    public List<Hit> find(Collection<UUID> jobIds, String query) {
        String trimmed = query == null ? "" : query.strip();
        if (trimmed.isEmpty() || jobIds.isEmpty()) {
            return List.of();
        }

        List<Object[]> rows = segments.search(jobIds, trimmed, OPEN, CLOSE, LIMIT);
        List<Hit> hits = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            hits.add(new Hit(
                    (UUID) row[0],
                    ((Number) row[1]).intValue(),
                    (String) row[2],
                    parts((String) row[3])));
        }
        return hits;
    }

    /**
     * Режет подсвеченный кусок на части: обычный текст и попадания.
     *
     * <p>Разбор здесь, а не в шаблоне: шаблон умеет только показывать.</p>
     */
    private static List<Part> parts(String headline) {
        List<Part> parts = new ArrayList<>();
        if (headline == null) {
            return parts;
        }

        int position = 0;
        while (position < headline.length()) {
            int open = headline.indexOf(OPEN, position);
            if (open < 0) {
                parts.add(new Part(headline.substring(position), false));
                break;
            }
            int close = headline.indexOf(CLOSE, open);
            if (close < 0) {
                parts.add(new Part(headline.substring(position), false));
                break;
            }
            if (open > position) {
                parts.add(new Part(headline.substring(position, open), false));
            }
            parts.add(new Part(headline.substring(open + 1, close), true));
            position = close + 1;
        }
        return parts;
    }

    /** Найденная реплика: чья задача, на какой секунде и что там сказано. */
    public record Hit(UUID jobId, int startMs, String speaker, List<Part> parts) {

        /** Секунды для перемотки плеера. */
        public double seconds() {
            return startMs / 1000.0;
        }

        /** {@code 1:02:03} у длинных записей, {@code 2:03} у коротких. */
        public String at() {
            int seconds = startMs / 1000;
            return seconds >= 3600
                    ? String.format("%d:%02d:%02d", seconds / 3600, (seconds % 3600) / 60, seconds % 60)
                    : String.format("%d:%02d", seconds / 60, seconds % 60);
        }
    }

    /** Кусок найденной реплики: {@code hit} — то, что совпало с запросом. */
    public record Part(String text, boolean hit) {}
}

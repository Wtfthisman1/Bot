package Bot.transcription;

/**
 * Расшифровка целиком: сегменты вместе с именами говорящих.
 *
 * <p>Ответственность: отдать страницам и экспорту одну картину вместо двух
 * таблиц. Имена в базе лежат только там, где человек их задал, а показывать
 * что-то нужно всегда — поэтому безымянным голосам здесь же выдаются
 * «Спикер 1», «Спикер 2» по порядку появления.</p>
 */
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record Transcript(List<TranscriptSegmentEntity> segments, Map<String, String> speakers) {

    /** Есть ли что показывать: у старых задач разметки нет вовсе. */
    public boolean isEmpty() {
        return segments.isEmpty();
    }

    /** Размечены ли голоса: без диаризации метка у всех сегментов пустая. */
    public boolean hasSpeakers() {
        return !speakers.isEmpty();
    }

    /** Как зовут этот голос: заданное имя, а если его нет — «Спикер N». */
    public String nameOf(String label) {
        return label == null ? null : speakers.getOrDefault(label, label);
    }

    /**
     * Собирает картину из того, что есть в базе.
     *
     * <p>Порядок «Спикера N» — по первому появлению в разговоре, а не по метке
     * диаризации: SPEAKER_01 может заговорить первым, и «Спикер 2» в начале
     * расшифровки выглядел бы ошибкой.</p>
     */
    static Transcript of(List<TranscriptSegmentEntity> segments,
                         List<TranscriptSpeakerEntity> named) {
        Map<String, String> custom = new LinkedHashMap<>();
        named.forEach(speaker -> custom.put(speaker.getLabel(), speaker.getName()));

        Map<String, String> names = new LinkedHashMap<>();
        int number = 1;
        for (TranscriptSegmentEntity segment : segments) {
            String label = segment.getSpeaker();
            if (label == null || names.containsKey(label)) {
                continue;
            }
            String given = custom.get(label);
            names.put(label, given != null && !given.isBlank() ? given : "Спикер " + number);
            number++;
        }
        return new Transcript(segments, names);
    }
}

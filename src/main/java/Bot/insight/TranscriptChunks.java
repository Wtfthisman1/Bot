package Bot.insight;

/**
 * Расшифровка, нарезанная на куски по размеру окна модели.
 *
 * <p>Ответственность: превратить сегменты в текст, который модель осилит за
 * один заход. Час разговора — это тысячи слов, и целиком он в окно не влезает:
 * модель молча забудет начало и перескажет только хвост, а человек этого не
 * увидит. Поэтому куски считаются отдельно, а потом сводятся вместе.</p>
 *
 * <p>Режется по границам реплик, а не по символам: разрезанная посередине фраза
 * попадает в оба куска половинами, и модель дважды пересказывает то, чего никто
 * не говорил.</p>
 *
 * <p>Перед каждой репликой стоит её время — по нему модель проставляет метки
 * в ответе, и человек одним щелчком попадает в нужное место записи. Без меток
 * выжимка остаётся текстом, к которому непонятно, где слушать.</p>
 */
import Bot.transcription.Timecode;
import Bot.transcription.Transcript;
import Bot.transcription.TranscriptSegmentEntity;

import java.util.ArrayList;
import java.util.List;

public final class TranscriptChunks {

    private TranscriptChunks() {
    }

    /**
     * @param maxChars предел на кусок в символах. Именно символы, а не токены:
     *                 токенизатор у каждой модели свой, а запас в окне всё равно
     *                 нужен под подсказку и ответ
     */
    public static List<Chunk> of(Transcript transcript, int maxChars) {
        List<Chunk> chunks = new ArrayList<>();
        if (transcript.isEmpty()) {
            return chunks;
        }

        StringBuilder current = new StringBuilder();
        int startMs = 0;
        int endMs = 0;

        for (TranscriptSegmentEntity segment : transcript.segments()) {
            String line = lineOf(transcript, segment);

            // Одна реплика длиннее предела — редкость (Whisper режет по паузам),
            // но если так, пусть уедет в свой кусок целиком: рвать её нельзя
            if (!current.isEmpty() && current.length() + line.length() > maxChars) {
                chunks.add(new Chunk(current.toString(), startMs, endMs));
                current.setLength(0);
            }
            if (current.isEmpty()) {
                startMs = segment.getStartMs();
            }
            current.append(line);
            endMs = segment.getEndMs();
        }

        if (!current.isEmpty()) {
            chunks.add(new Chunk(current.toString(), startMs, endMs));
        }
        return chunks;
    }

    /** Сколько слов в расшифровке — от этого считается длина выжимки. */
    public static int words(Transcript transcript) {
        int words = 0;
        for (TranscriptSegmentEntity segment : transcript.segments()) {
            for (String part : segment.getText().strip().split("\\s+")) {
                if (!part.isBlank()) {
                    words++;
                }
            }
        }
        return words;
    }

    private static String lineOf(Transcript transcript, TranscriptSegmentEntity segment) {
        String name = transcript.nameOf(segment.getSpeaker());
        return "[%s] %s%s\n".formatted(
                Timecode.format(segment.getStartMs()),
                name == null ? "" : name + ": ",
                segment.getText().strip());
    }

    /** Кусок разговора: текст с метками времени и границы этого куска. */
    public record Chunk(String text, int startMs, int endMs) {

        /**
         * Границы куска по времени: «0:00 до 5:59».
         *
         * <p>Словом «до», а не через тире: тире модель принимает за образец и
         * начинает ставить в ответе диапазоны — «[0:19-0:52]» вместо метки
         * начала. Проверено на живой записи.</p>
         */
        public String range() {
            return Timecode.format(startMs) + " до " + Timecode.format(endMs);
        }
    }
}

package Bot.transcription;

/**
 * Расшифровка из базы обратно в текст и субтитры.
 *
 * <p>Ответственность: собрать файл из сегментов — с правками человека и
 * именами голосов. Whisper пишет свои .txt и .srt один раз, в момент
 * распознавания, и о правках не знает; всё, что человек исправил в кабинете,
 * доходит до скачанного файла только отсюда.</p>
 *
 * <p>Имя говорящего попадает и в субтитры: в переговорах и интервью подпись
 * «кто» важнее лишней строки на экране.</p>
 */
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class TranscriptRenderer {

    /** Текст: подпись голоса выносится строкой, пока голос не сменится. */
    public String asText(Transcript transcript) {
        StringBuilder out = new StringBuilder();
        String current = null;

        for (TranscriptSegmentEntity segment : transcript.segments()) {
            String speaker = segment.getSpeaker();
            if (speaker != null && !speaker.equals(current)) {
                if (!out.isEmpty()) {
                    out.append('\n');
                }
                out.append(transcript.nameOf(speaker)).append(":\n");
                current = speaker;
            }
            out.append(segment.getText()).append('\n');
        }
        return out.toString();
    }

    public String asSrt(Transcript transcript) {
        StringBuilder out = new StringBuilder();
        List<TranscriptSegmentEntity> segments = transcript.segments();

        for (int i = 0; i < segments.size(); i++) {
            TranscriptSegmentEntity segment = segments.get(i);
            out.append(i + 1).append('\n')
                    .append(timecode(segment.getStartMs(), ',')).append(" --> ")
                    .append(timecode(segment.getEndMs(), ',')).append('\n')
                    .append(caption(transcript, segment)).append("\n\n");
        }
        return out.toString();
    }

    public String asVtt(Transcript transcript) {
        StringBuilder out = new StringBuilder("WEBVTT\n\n");

        for (TranscriptSegmentEntity segment : transcript.segments()) {
            out.append(timecode(segment.getStartMs(), '.')).append(" --> ")
                    .append(timecode(segment.getEndMs(), '.')).append('\n')
                    .append(caption(transcript, segment)).append("\n\n");
        }
        return out.toString();
    }

    /** Что именно этот формат умеет собрать из сегментов. */
    public String render(Transcript transcript, TranscriptFormat format) {
        return switch (format) {
            case SRT -> asSrt(transcript);
            case VTT -> asVtt(transcript);
            // Word собирается из текста: ему нужен не таймкод, а абзацы
            case TXT, DOCX -> asText(transcript);
        };
    }

    private static String caption(Transcript transcript, TranscriptSegmentEntity segment) {
        String name = transcript.nameOf(segment.getSpeaker());
        return name == null ? segment.getText() : name + ": " + segment.getText();
    }

    /** {@code 00:01:02,340} для SRT и {@code 00:01:02.340} для VTT. */
    private static String timecode(int millis, char fraction) {
        int totalSeconds = millis / 1000;
        return String.format("%02d:%02d:%02d%c%03d",
                totalSeconds / 3600, (totalSeconds % 3600) / 60, totalSeconds % 60,
                fraction, millis % 1000);
    }
}

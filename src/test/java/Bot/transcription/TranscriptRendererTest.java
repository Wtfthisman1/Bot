package Bot.transcription;

/**
 * Сборка файлов из сегментов: правки и имена голосов должны доезжать до
 * скачанного текста и до субтитров.
 */
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TranscriptRendererTest {

    private final TranscriptRenderer renderer = new TranscriptRenderer();

    @Test
    void textGroupsLinesBySpeaker() {
        Transcript transcript = transcript(
                segment(0, 1000, "SPEAKER_00", "Начнём."),
                segment(1000, 2000, "SPEAKER_00", "Первый вопрос."),
                segment(2000, 3000, "SPEAKER_01", "Слушаю."));

        assertThat(renderer.asText(transcript)).isEqualTo("""
                Спикер 1:
                Начнём.
                Первый вопрос.

                Спикер 2:
                Слушаю.
                """);
    }

    /** Имя, данное человеком, вытесняет «Спикера N» везде. */
    @Test
    void givenNameWins() {
        Transcript transcript = Transcript.of(
                List.of(segment(0, 1000, "SPEAKER_00", "Здравствуйте.")),
                List.of(new TranscriptSpeakerEntity(UUID.randomUUID(), "SPEAKER_00", "Ведущий")));

        assertThat(renderer.asText(transcript)).startsWith("Ведущий:");
        assertThat(renderer.asSrt(transcript)).contains("Ведущий: Здравствуйте.");
    }

    /** Без диаризации подписей нет вовсе — текст остаётся сплошным. */
    @Test
    void withoutSpeakersTextIsPlain() {
        Transcript transcript = transcript(
                segment(0, 1000, null, "Раз."),
                segment(1000, 2000, null, "Два."));

        assertThat(renderer.asText(transcript)).isEqualTo("Раз.\nДва.\n");
    }

    @Test
    void srtNumbersAndTimecodes() {
        String srt = renderer.asSrt(transcript(
                segment(0, 2500, null, "Раз."),
                segment(3600_000, 3_601_250, null, "Два.")));

        assertThat(srt).isEqualTo("""
                1
                00:00:00,000 --> 00:00:02,500
                Раз.

                2
                01:00:00,000 --> 01:00:01,250
                Два.

                """);
    }

    @Test
    void vttStartsWithItsHeaderAndUsesDots() {
        String vtt = renderer.asVtt(transcript(segment(1000, 2000, null, "Раз.")));

        assertThat(vtt).startsWith("WEBVTT")
                .contains("00:00:01.000 --> 00:00:02.000");
    }

    /* ───────── helpers ───────── */

    private static Transcript transcript(TranscriptSegmentEntity... segments) {
        return Transcript.of(List.of(segments), List.of());
    }

    private static TranscriptSegmentEntity segment(int start, int end, String speaker, String text) {
        TranscriptSegmentEntity segment = new TranscriptSegmentEntity();
        segment.setStartMs(start);
        segment.setEndMs(end);
        segment.setSpeaker(speaker);
        segment.setText(text);
        return segment;
    }
}

package Bot.insight;

/**
 * Нарезка расшифровки под окно модели.
 *
 * <p>Проверяется то, из-за чего пересказ молча портится: разрезанная посередине
 * реплика и потерянные метки времени.</p>
 */
import Bot.transcription.Transcript;
import Bot.transcription.TranscriptSegmentEntity;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TranscriptChunksTest {

    @Test
    void shortTranscriptFitsInOneChunk() {
        Transcript transcript = of(
                segment(0, 2_000, null, "Привет."),
                segment(2_000, 5_000, null, "И тебе привет."));

        List<TranscriptChunks.Chunk> chunks = TranscriptChunks.of(transcript, 6000);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).text())
                .contains("[0:00] Привет.")
                .contains("[0:02] И тебе привет.");
        assertThat(chunks.get(0).range()).isEqualTo("0:00 до 0:05");
    }

    @Test
    void repliesAreNeverCutInHalf() {
        List<TranscriptSegmentEntity> segments = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            segments.add(segment(i * 10_000, i * 10_000 + 9_000, null, "Реплика номер " + i + "."));
        }

        List<TranscriptChunks.Chunk> chunks = TranscriptChunks.of(new Transcript(segments, Map.of()), 100);

        assertThat(chunks).hasSizeGreaterThan(1);
        for (TranscriptChunks.Chunk chunk : chunks) {
            for (String line : chunk.text().strip().split("\n")) {
                // Каждая строка куска — целая реплика со своей меткой времени
                assertThat(line).matches("\\[\\d+:\\d{2}] Реплика номер \\d+\\.");
            }
        }
        // Ни одна не потерялась и ни одна не задвоилась
        assertThat(chunks.stream().mapToInt(c -> c.text().strip().split("\n").length).sum())
                .isEqualTo(20);
    }

    @Test
    void speakerNamesGoToTheModel() {
        Transcript transcript = new Transcript(
                List.of(segment(0, 2_000, "SPEAKER_00", "Начнём.")),
                Map.of("SPEAKER_00", "Ведущий"));

        assertThat(TranscriptChunks.of(transcript, 6000).get(0).text())
                .isEqualTo("[0:00] Ведущий: Начнём.\n");
    }

    @Test
    void wordsAreCountedForTheSummaryLength() {
        Transcript transcript = of(
                segment(0, 1_000, null, "Раз два три"),
                segment(1_000, 2_000, null, "четыре пять"));

        assertThat(TranscriptChunks.words(transcript)).isEqualTo(5);
    }

    @Test
    void emptyTranscriptGivesNothingToAsk() {
        assertThat(TranscriptChunks.of(new Transcript(List.of(), Map.of()), 6000)).isEmpty();
    }

    /* ───────── helpers ───────── */

    private static Transcript of(TranscriptSegmentEntity... segments) {
        return new Transcript(List.of(segments), Map.of());
    }

    private static TranscriptSegmentEntity segment(int startMs, int endMs, String speaker, String text) {
        TranscriptSegmentEntity segment = new TranscriptSegmentEntity();
        segment.setStartMs(startMs);
        segment.setEndMs(endMs);
        segment.setSpeaker(speaker);
        segment.setText(text);
        return segment;
    }
}

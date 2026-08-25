package Bot.transcription;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Соседние файлы ищутся заменой расширения — а имена приходят с площадок
 * и точек внутри содержат сколько угодно.
 */
class TranscriptFormatTest {

    @Test
    void siblingKeepsDotsInsideTheName() {
        Path txt = Path.of("/data", "Лекция 3. Стресс.txt");

        assertThat(TranscriptFormat.SRT.fileFor(txt))
                .isEqualTo(Path.of("/data", "Лекция 3. Стресс.srt"));
    }

    @Test
    void siblingWorksWithoutExtension() {
        assertThat(TranscriptFormat.VTT.fileFor(Path.of("/data", "transcript")))
                .isEqualTo(Path.of("/data", "transcript.vtt"));
    }

    @Test
    void codesAreParsedBack() {
        for (TranscriptFormat format : TranscriptFormat.values()) {
            assertThat(TranscriptFormat.fromCode(format.code())).contains(format);
        }
    }

    @Test
    void unknownCodeIsNotAnError() {
        assertThat(TranscriptFormat.fromCode("pdf")).isEmpty();
    }

    /** Кнопка не должна упереться в лимит Telegram — 64 байта на callback_data. */
    @Test
    void callbackDataFitsTelegramLimit() {
        for (TranscriptFormat format : TranscriptFormat.values()) {
            String callback = "tr:" + format.code() + ':' + "x".repeat(12);
            assertThat(callback.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                    .isLessThanOrEqualTo(64);
        }
    }
}

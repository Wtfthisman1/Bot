package Bot.transcription;

import Bot.telegram.MessageSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Выдача расшифровки: кнопки обещают только те форматы, которые есть на диске,
 * а нажатие отдаёт именно этот файл.
 */
@ExtendWith(MockitoExtension.class)
class TranscriptDeliveryServiceTest {

    private static final long CHAT = 7L;

    @TempDir Path tmp;

    @Mock private MessageSender messageSender;

    private TranscriptRegistry registry;
    private TranscriptDeliveryService delivery;
    private Path txt;

    @BeforeEach
    void setUp() throws IOException {
        registry = new TranscriptRegistry();
        ReflectionTestUtils.setField(registry, "storePath", tmp.resolve("store.tsv").toString());
        ReflectionTestUtils.invokeMethod(registry, "load");

        delivery = new TranscriptDeliveryService(registry, new WordExporter(), messageSender);
        txt = Files.writeString(tmp.resolve("лекция.txt"), "текст расшифровки");
    }

    @Test
    void offersSubtitlesOnlyWhenWhisperProducedThem() throws IOException {
        Files.writeString(tmp.resolve("лекция.srt"), "1\n00:00:00,000 --> 00:00:01,000\nтекст\n");

        delivery.deliver(CHAT, txt);

        // .srt есть, .vtt нет; Word собирается из текста всегда
        assertThat(buttonLabels()).containsExactly(
                TranscriptFormat.SRT.buttonLabel(), TranscriptFormat.DOCX.buttonLabel());
    }

    @Test
    void buttonSendsRequestedSubtitleFile() throws IOException {
        Path srt = Files.writeString(tmp.resolve("лекция.srt"), "субтитры");
        delivery.deliver(CHAT, txt);

        delivery.sendFormat(CHAT, idFromKeyboard(), TranscriptFormat.SRT);

        verify(messageSender).sendFile(eq(CHAT), eq(srt), eq(TranscriptFormat.SRT.caption()), any());
    }

    /** Word собирается на лету — отдельного файла на диске до нажатия нет. */
    @Test
    void wordIsBuiltOnDemand() {
        delivery.deliver(CHAT, txt);

        delivery.sendFormat(CHAT, idFromKeyboard(), TranscriptFormat.DOCX);

        ArgumentCaptor<Path> sent = ArgumentCaptor.forClass(Path.class);
        verify(messageSender).sendFile(eq(CHAT), sent.capture(), eq(TranscriptFormat.DOCX.caption()), any());
        assertThat(sent.getValue()).exists().hasFileName("лекция.docx");
    }

    /** Устаревшая кнопка не должна оставлять пользователя без ответа. */
    @Test
    void staleButtonExplainsItselfInsteadOfSilence() {
        delivery.sendFormat(CHAT, "нет-такого", TranscriptFormat.SRT);

        verify(messageSender).sendMessageWithKeyboard(eq(CHAT), any(), isNull(), any());
        verify(messageSender, never()).sendFile(anyLong(), any(), any(), any());
    }

    /** Формат, которого нет, не отдаётся: пользователь получает объяснение. */
    @Test
    void missingFormatIsReportedNotSent() {
        delivery.deliver(CHAT, txt);

        delivery.sendFormat(CHAT, idFromKeyboard(), TranscriptFormat.VTT);

        verify(messageSender).sendMessageWithKeyboard(eq(CHAT), any(), isNull(), any());
        verify(messageSender, never()).sendFile(anyLong(), any(), any(), any());
    }

    /* ───────── helpers ───────── */

    private InlineKeyboardMarkup keyboard() {
        ArgumentCaptor<InlineKeyboardMarkup> markup = ArgumentCaptor.forClass(InlineKeyboardMarkup.class);
        verify(messageSender).sendTranscript(eq(CHAT), eq(txt), markup.capture());
        return markup.getValue();
    }

    private List<String> buttonLabels() {
        return keyboard().getKeyboard().stream()
                .flatMap(List::stream)
                .map(InlineKeyboardButton::getText)
                .toList();
    }

    /** Идентификатор расшифровки достаём оттуда же, откуда его берёт Telegram. */
    private String idFromKeyboard() {
        String callback = keyboard().getKeyboard().get(0).get(0).getCallbackData();
        return callback.split(":", 3)[2];
    }
}

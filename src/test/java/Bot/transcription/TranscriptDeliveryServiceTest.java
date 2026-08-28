package Bot.transcription;

import Bot.insight.InsightService;
import Bot.owner.Owner;
import Bot.telegram.Keyboards;
import Bot.processing.JobStore;
import Bot.telegram.MessageSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Выдача расшифровки: кнопки обещают только те форматы, которые есть на диске,
 * а нажатие отдаёт именно этот файл.
 */
@ExtendWith(MockitoExtension.class)
class TranscriptDeliveryServiceTest {

    private static final long CHAT = 7L;

    @TempDir Path tmp;

    @Mock private MessageSender messageSender;
    @Mock private JobStore jobStore;
    @Mock private InsightService insights;

    private TranscriptDeliveryService delivery;
    private Path txt;

    /** Задача, под которой пришла расшифровка: её id стоит в кнопках. */
    private static final java.util.UUID JOB = java.util.UUID.randomUUID();

    @BeforeEach
    void setUp() throws IOException {
        delivery = new TranscriptDeliveryService(
                jobStore, new WordExporter(), messageSender, insights);
        txt = Files.writeString(tmp.resolve("лекция.txt"), "текст расшифровки");

        // Кнопки находят расшифровку по задаче — мок отвечает так же, как база
        lenient().when(jobStore.transcriptOf(JOB, Owner.telegram(CHAT)))
                .thenReturn(java.util.Optional.of(txt));
    }

    @Test
    void offersSubtitlesOnlyWhenWhisperProducedThem() throws IOException {
        Files.writeString(tmp.resolve("лекция.srt"), "1\n00:00:00,000 --> 00:00:01,000\nтекст\n");

        delivery.deliver(JOB, CHAT, txt);

        // .srt есть, .vtt нет; Word собирается из текста всегда
        assertThat(buttonLabels()).containsExactly(
                TranscriptFormat.SRT.buttonLabel(), TranscriptFormat.DOCX.buttonLabel());
    }

    @Test
    void buttonSendsRequestedSubtitleFile() throws IOException {
        Path srt = Files.writeString(tmp.resolve("лекция.srt"), "субтитры");
        delivery.deliver(JOB, CHAT, txt);

        delivery.sendFormat(CHAT, idFromKeyboard(), TranscriptFormat.SRT);

        verify(messageSender).sendFile(eq(CHAT), eq(srt), eq(TranscriptFormat.SRT.caption()), any());
    }

    /** Word собирается на лету — отдельного файла на диске до нажатия нет. */
    @Test
    void wordIsBuiltOnDemand() {
        delivery.deliver(JOB, CHAT, txt);

        delivery.sendFormat(CHAT, idFromKeyboard(), TranscriptFormat.DOCX);

        ArgumentCaptor<Path> sent = ArgumentCaptor.forClass(Path.class);
        verify(messageSender).sendFile(eq(CHAT), sent.capture(), eq(TranscriptFormat.DOCX.caption()), any());
        assertThat(sent.getValue()).exists().hasFileName("лекция.docx");
    }

    /**
     * Выжимку обещаем, только когда модель отвечает: кнопка, за которой ничего
     * нет, хуже отсутствующей.
     */
    @Test
    void summaryIsOfferedOnlyWhenModelAnswers() {
        when(insights.ready()).thenReturn(true);

        delivery.deliver(JOB, CHAT, txt);

        assertThat(buttonLabels()).contains("✨ Выжимка");
        assertThat(summaryCallback()).isEqualTo(Keyboards.CB_SUMMARY_PREFIX + JOB);
    }

    @Test
    void withoutModelThereIsNoSummaryButton() {
        delivery.deliver(JOB, CHAT, txt);

        assertThat(buttonLabels()).doesNotContain("✨ Выжимка");
    }

    /**
     * Модель есть, а субтитров нет — расшифровка всё равно уходит с кнопкой:
     * раньше пустой список форматов означал сообщение вовсе без клавиатуры.
     */
    @Test
    void summaryComesEvenWhenNoFormatsAreAvailable() {
        // Файла нет вовсе: ни субтитров рядом, ни текста, из которого собрать Word
        Path bare = tmp.resolve("удалённая.txt");
        when(insights.ready()).thenReturn(true);

        delivery.deliver(JOB, CHAT, bare);

        ArgumentCaptor<InlineKeyboardMarkup> markup = ArgumentCaptor.forClass(InlineKeyboardMarkup.class);
        verify(messageSender).sendTranscript(eq(CHAT), eq(bare), markup.capture());
        assertThat(markup.getValue().getKeyboard()).hasSize(1);
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
        delivery.deliver(JOB, CHAT, txt);

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

    /** Callback кнопки выжимки — она всегда идёт последним рядом. */
    private String summaryCallback() {
        List<List<InlineKeyboardButton>> rows = keyboard().getKeyboard();
        return rows.get(rows.size() - 1).get(0).getCallbackData();
    }

    /** Идентификатор расшифровки достаём оттуда же, откуда его берёт Telegram. */
    private String idFromKeyboard() {
        String callback = keyboard().getKeyboard().get(0).get(0).getCallbackData();
        return callback.split(":", 3)[2];
    }
}

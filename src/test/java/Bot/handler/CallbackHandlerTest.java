package Bot.handler;

import Bot.handler.UserSessionService.Mode;
import Bot.handler.UserSessionService.Pending;
import Bot.processing.MediaKind;
import Bot.home.HomeApi;
import Bot.owner.Owner;
import Bot.telegram.Keyboards;
import Bot.transcription.TranscriptFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Сценарий из ТЗ: кнопка → «пришлите ссылку», и обратный порядок —
 * ссылка уже прислана, кнопка запускает работу сразу.
 */
@ExtendWith(MockitoExtension.class)
class CallbackHandlerTest {

    private static final long CHAT = 5L;

    @Mock private UrlActionService urlActionService;
    @Mock private CommandHandler commandHandler;
    @Mock private HomeApi home;

    private final UserSessionService sessions = new UserSessionService();

    private CallbackHandler handler() {
        return new CallbackHandler(sessions, urlActionService, commandHandler, home);
    }

    @Test
    void transcribeButtonAsksForLinkWhenNothingPending() {
        handler().handle(CHAT, Keyboards.CB_TRANSCRIBE, "Аня");

        verify(commandHandler).askForLink(CHAT, Mode.TRANSCRIBE, MediaKind.AUDIO);
        verifyNoInteractions(urlActionService);
    }

    /** «Скачать» ничего не запускает само — сперва спрашивает формат. */
    @Test
    void downloadButtonAsksForFormatFirst() {
        handler().handle(CHAT, Keyboards.CB_DOWNLOAD, "Аня");

        verify(commandHandler).askDownloadKind(CHAT);
        verify(commandHandler, never()).askForLink(anyLong(), any(), any());
        verifyNoInteractions(urlActionService);
    }

    @Test
    void formatButtonAsksForLinkWhenNothingPending() {
        handler().handle(CHAT, Keyboards.CB_DL_VIDEO, "Аня");

        verify(commandHandler).askForLink(CHAT, Mode.DOWNLOAD, MediaKind.VIDEO);
    }

    @Test
    void audioFormatIsCarriedThrough() {
        handler().handle(CHAT, Keyboards.CB_DL_AUDIO, "Аня");

        verify(commandHandler).askForLink(CHAT, Mode.DOWNLOAD, MediaKind.AUDIO);
    }

    @Test
    void buttonStartsImmediatelyWhenLinkArrivedFirst() {
        sessions.rememberUrl(CHAT, "https://youtu.be/x");

        handler().handle(CHAT, Keyboards.CB_DL_VIDEO, "Аня");

        verify(urlActionService).start(CHAT, new Pending(Mode.DOWNLOAD, MediaKind.VIDEO),
                "https://youtu.be/x", "Аня");
        verify(commandHandler, never()).askForLink(anyLong(), any(), any());
    }

    /**
     * Ссылка прислана первой, затем «Скачать» → «Аудио»: промежуточный вопрос
     * о формате не должен съедать отложенную ссылку.
     */
    @Test
    void pendingUrlSurvivesTheFormatQuestion() {
        sessions.rememberUrl(CHAT, "https://youtu.be/x");
        CallbackHandler handler = handler();

        handler.handle(CHAT, Keyboards.CB_DOWNLOAD, "Аня");
        handler.handle(CHAT, Keyboards.CB_DL_AUDIO, "Аня");

        verify(urlActionService).start(CHAT, new Pending(Mode.DOWNLOAD, MediaKind.AUDIO),
                "https://youtu.be/x", "Аня");
        verify(commandHandler, never()).askForLink(anyLong(), any(), any());
    }

    @Test
    void pendingUrlIsConsumedSoSecondPressDoesNotRestart() {
        sessions.rememberUrl(CHAT, "https://youtu.be/x");
        CallbackHandler handler = handler();

        handler.handle(CHAT, Keyboards.CB_DL_VIDEO, "Аня");
        handler.handle(CHAT, Keyboards.CB_DL_VIDEO, "Аня");

        verify(urlActionService).start(CHAT, new Pending(Mode.DOWNLOAD, MediaKind.VIDEO),
                "https://youtu.be/x", "Аня");
        verify(commandHandler).askForLink(CHAT, Mode.DOWNLOAD, MediaKind.VIDEO);
    }

    @Test
    void menuButtonsReachTheirScreens() {
        CallbackHandler handler = handler();

        handler.handle(CHAT, Keyboards.CB_UPLOAD, "Аня");
        handler.handle(CHAT, Keyboards.CB_STATUS, "Аня");
        handler.handle(CHAT, Keyboards.CB_HELP, "Аня");
        handler.handle(CHAT, Keyboards.CB_CANCEL, "Аня");

        verify(commandHandler).upload(CHAT);
        verify(commandHandler).status(CHAT);
        verify(commandHandler).help(CHAT);
        verify(commandHandler).cancel(CHAT);
    }

    @Test
    void unknownCallbackFallsBackToMenu() {
        handler().handle(CHAT, "action:transcribe:12345", "Аня");

        verify(commandHandler).showMenu(eq(CHAT), any());
        verifyNoInteractions(urlActionService);
    }

    /** Кнопка формата под расшифровкой ведёт в выдачу, а не в меню. */
    @Test
    void transcriptFormatButtonDeliversThatFormat() {
        handler().handle(CHAT, Keyboards.transcriptCallback(TranscriptFormat.SRT, "abc123"), "Аня");

        verify(home).sendTranscript(Owner.telegram(CHAT), "abc123", TranscriptFormat.SRT);
        verifyNoInteractions(commandHandler);
    }

    /** Битый callback — не повод молчать: показываем меню. */
    @Test
    void brokenTranscriptCallbackShowsMenu() {
        handler().handle(CHAT, "tr:srt:", "Аня");

        verify(commandHandler).showMenu(eq(CHAT), any());
        verifyNoInteractions(home);
    }

    /** Неизвестный формат в кнопке не должен доходить до выдачи файлов. */
    @Test
    void unknownFormatCodeShowsMenu() {
        handler().handle(CHAT, "tr:pdf:abc123", "Аня");

        verify(commandHandler).showMenu(eq(CHAT), any());
        verifyNoInteractions(home);
    }

    /**
     * Кнопка «Выжимка» заказывает счёт и сразу отвечает: текст придёт минутами
     * позже, отдельным сообщением из дома.
     */
    @Test
    void summaryButtonOrdersItAndPromisesAnAnswer() {
        when(home.summarize(Owner.telegram(CHAT), "abc123")).thenReturn(Optional.empty());

        handler().handle(CHAT, Keyboards.CB_SUMMARY_PREFIX + "abc123", "Аня");

        verify(home).summarize(Owner.telegram(CHAT), "abc123");
        verify(commandHandler).showMenu(eq(CHAT), contains("Считаю выжимку"));
    }

    /** Отказ дома — это ответ человеку, а не молчание. */
    @Test
    void refusedSummaryIsExplained() {
        when(home.summarize(Owner.telegram(CHAT), "abc123"))
                .thenReturn(Optional.of("Предыдущая обработка ещё считается — дождитесь её."));

        handler().handle(CHAT, Keyboards.CB_SUMMARY_PREFIX + "abc123", "Аня");

        verify(commandHandler).showMenu(eq(CHAT), contains("ещё считается"));
    }

    /** Кнопка без задачи — из очень старого сообщения: в дом за этим не ходим. */
    @Test
    void summaryWithoutJobShowsMenu() {
        handler().handle(CHAT, Keyboards.CB_SUMMARY_PREFIX, "Аня");

        verify(commandHandler).showMenu(eq(CHAT), any());
        verifyNoInteractions(home);
    }
}

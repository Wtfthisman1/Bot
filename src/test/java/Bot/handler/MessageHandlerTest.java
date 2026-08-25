package Bot.handler;

import Bot.handler.UserSessionService.Mode;
import Bot.handler.UserSessionService.Pending;
import Bot.processing.MediaKind;
import Bot.telegram.MessageSender;
import Bot.telegram.TelegramFileDownloader;
import Bot.transcription.TranscribeExecutor;
import Bot.transcription.TranscriptDeliveryService;
import Bot.upload.UploadService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.SyncTaskExecutor;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Разбор текстовых сообщений: ссылка запускает выбранное действие,
 * а без выбора — откладывается до нажатия кнопки.
 */
@ExtendWith(MockitoExtension.class)
class MessageHandlerTest {

    private static final long CHAT = 11L;
    private static final String URL = "https://youtu.be/dQw4w9WgXcQ";

    @Mock private TranscribeExecutor transcriber;
    @Mock private TranscriptDeliveryService transcriptDelivery;
    @Mock private TelegramFileDownloader fileDownloader;
    @Mock private MessageSender messageSender;
    @Mock private UploadService uploadService;
    @Mock private UrlActionService urlActionService;
    @Mock private CommandHandler commandHandler;

    private final UserSessionService sessions = new UserSessionService();
    private MessageHandler handler;

    @BeforeEach
    void setUp() {
        handler = new MessageHandler(new SyncTaskExecutor(), transcriber, transcriptDelivery,
                fileDownloader, messageSender, uploadService, sessions, urlActionService,
                commandHandler);
    }

    @Test
    void linkAfterChosenActionStartsThatAction() {
        sessions.awaitLink(CHAT, Mode.DOWNLOAD, MediaKind.AUDIO);

        handler.handleText(CHAT, "вот: " + URL, "Аня");

        verify(urlActionService).start(CHAT, new Pending(Mode.DOWNLOAD, MediaKind.AUDIO),
                List.of(URL), "Аня");
    }

    @Test
    void linkWithoutChosenActionIsRememberedAndButtonsShown() {
        handler.handleText(CHAT, URL, "Аня");

        verifyNoInteractions(urlActionService);
        verify(messageSender).sendMessageWithKeyboard(eq(CHAT), any(), eq(null), any());
        // ссылка ждёт нажатия кнопки, а не потеряна
        org.assertj.core.api.Assertions.assertThat(sessions.takePendingUrl(CHAT)).contains(URL);
    }

    @Test
    void nonLinkWhileAwaitingKeepsWaiting() {
        sessions.awaitLink(CHAT, Mode.TRANSCRIBE, MediaKind.AUDIO);

        handler.handleText(CHAT, "привет", "Аня");

        verify(messageSender).sendMessageWithKeyboard(eq(CHAT), any(), eq(null), any());
        verify(commandHandler, never()).showMenu(anyLong(), any());
        // состояние не сброшено: следующая ссылка всё ещё уйдёт в транскрипцию
        org.assertj.core.api.Assertions.assertThat(sessions.isAwaitingLink(CHAT)).isTrue();
    }

    @Test
    void plainTextWithoutStateShowsMenu() {
        handler.handleText(CHAT, "привет", "Аня");

        verify(commandHandler).showMenu(eq(CHAT), any());
        verifyNoInteractions(urlActionService);
    }

    @Test
    void severalLinksGoToTheChosenActionTogether() {
        sessions.awaitLink(CHAT, Mode.TRANSCRIBE, MediaKind.AUDIO);

        handler.handleText(CHAT, "https://youtu.be/1 и https://vimeo.com/2", "Аня");

        verify(urlActionService).start(eq(CHAT), eq(new Pending(Mode.TRANSCRIBE, MediaKind.AUDIO)),
                anyList(), eq("Аня"));
    }
}

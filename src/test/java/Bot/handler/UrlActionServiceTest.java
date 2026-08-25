package Bot.handler;

import Bot.download.DownloadService;
import Bot.handler.UserSessionService.Mode;
import Bot.handler.UserSessionService.Pending;
import Bot.processing.MediaKind;
import Bot.processing.JobQueue;
import Bot.processing.ProcessingJob;
import Bot.service.SupportedPlatforms;
import Bot.telegram.MessageSender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class UrlActionServiceTest {

    private static final long CHAT = 7L;
    private static final String URL = "https://youtu.be/dQw4w9WgXcQ";

    @Mock private JobQueue jobQueue;
    @Mock private DownloadService downloadService;
    @Mock private MessageSender messageSender;
    @org.mockito.Spy private SupportedPlatforms supportedPlatforms = new SupportedPlatforms();

    @InjectMocks private UrlActionService service;

    private static Pending transcribe() {
        return new Pending(Mode.TRANSCRIBE, MediaKind.AUDIO);
    }

    private static Pending download(MediaKind media) {
        return new Pending(Mode.DOWNLOAD, media);
    }

    @Test
    void transcribeEnqueuesLinkJobAndConfirms() {
        boolean started = service.start(CHAT, transcribe(), URL, "Аня");

        assertThat(started).isTrue();
        ArgumentCaptor<ProcessingJob> job = ArgumentCaptor.forClass(ProcessingJob.class);
        verify(jobQueue).enqueue(job.capture());
        assertThat(job.getValue().url()).isEqualTo(URL);
        assertThat(job.getValue().downloadId()).isNull();
        verify(messageSender).sendMessage(CHAT, "✅ Всё запущено, ожидайте.");
        verifyNoInteractions(downloadService);
    }

    @Test
    void downloadCreatesDownloadTaskAndConfirms() {
        boolean started = service.start(CHAT, download(MediaKind.VIDEO), URL, "Аня");

        assertThat(started).isTrue();
        verify(downloadService).createDownloadTask(CHAT, URL, "Аня", MediaKind.VIDEO);
        verify(messageSender).sendMessage(CHAT, "✅ Всё запущено, ожидайте.");
        verifyNoInteractions(jobQueue);
    }

    @Test
    void unsupportedUrlIsRejectedWithoutStartingAnything() {
        boolean started = service.start(CHAT, download(MediaKind.VIDEO), "http://evil.example/file.mp4", "Аня");

        assertThat(started).isFalse();
        verifyNoInteractions(jobQueue, downloadService);
        verify(messageSender).sendMessageWithKeyboard(eq(CHAT), any(), eq(null), any());
        verify(messageSender, never()).sendMessage(anyLong(), any());
    }

    @Test
    void severalLinksProduceOneConfirmationAndAreCapped() {
        List<String> urls = List.of(
                "https://youtu.be/1", "https://youtu.be/2", "https://youtu.be/3",
                "https://youtu.be/4", "https://youtu.be/5", "https://youtu.be/6");

        service.start(CHAT, transcribe(), urls, "Аня");

        verify(jobQueue, org.mockito.Mockito.times(UrlActionService.MAX_URLS_PER_MESSAGE))
                .enqueue(any());
        verify(messageSender).sendMessage(CHAT, "✅ Всё запущено, ожидайте.");
    }

    /** Выбранный формат обязан долететь до задачи загрузки, а не потеряться. */
    @Test
    void chosenMediaKindReachesTheDownloadTask() {
        service.start(CHAT, download(MediaKind.AUDIO), URL, "Аня");

        verify(downloadService).createDownloadTask(CHAT, URL, "Аня", MediaKind.AUDIO);
    }

    @Test
    void unsupportedLinksAreFilteredOutOfMixedBatch() {
        service.start(CHAT, transcribe(),
                List.of("http://evil.example/x", "https://vimeo.com/123"), "Аня");

        ArgumentCaptor<ProcessingJob> job = ArgumentCaptor.forClass(ProcessingJob.class);
        verify(jobQueue).enqueue(job.capture());
        assertThat(job.getValue().url()).isEqualTo("https://vimeo.com/123");
    }
}

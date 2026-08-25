package Bot.download;

import Bot.processing.JobStore;
import Bot.service.SupportedPlatforms;
import Bot.telegram.MessageSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Сквозная проверка обещания «нажал Скачать — получил рабочую ссылку»:
 * ссылка из сообщения бота реально отдаёт файл через HTTP.
 */
@ExtendWith(MockitoExtension.class)
class DownloadLinkFlowTest {

    private static final long CHAT = 99L;
    private static final String BASE_URL = "http://bot.example:8080";
    private static final Pattern HREF = Pattern.compile("href=\"([^\"]+)\"");

    @TempDir Path tmp;

    @Mock private JobStore jobStore;
    @Mock private MessageSender messageSender;

    private DownloadTokenRegistry registry;
    private DownloadService downloadService;
    private MockMvc mockMvc;
    private Path videoFile;

    @BeforeEach
    void setUp() throws IOException {
        videoFile = Files.writeString(tmp.resolve("Ролик & друзья.mp4"), "video-bytes");

        registry = new DownloadTokenRegistry();
        ReflectionTestUtils.setField(registry, "ttlHours", 24);
        ReflectionTestUtils.setField(registry, "storePath", tmp.resolve("tokens.tsv").toString());
        ReflectionTestUtils.invokeMethod(registry, "load");

        downloadService = new DownloadService(jobStore, messageSender, registry, new SupportedPlatforms());
        ReflectionTestUtils.setField(downloadService, "downloadBaseUrl", BASE_URL + "/");
        ReflectionTestUtils.setField(downloadService, "telegramMaxBytes", 52_428_800L);
        ReflectionTestUtils.setField(downloadService, "linkTtlHours", 24);
        ReflectionTestUtils.invokeMethod(downloadService, "init");

        mockMvc = MockMvcBuilders.standaloneSetup(new DownloadController(registry)).build();
    }

    /** Полный путь: задача → завершение → ссылка в сообщении → HTTP-скачивание. */
    @Test
    void issuedLinkActuallyServesTheFile() throws Exception {
        String link = completeDownloadAndCaptureLink();

        assertThat(link).startsWith(BASE_URL + "/download/");

        mockMvc.perform(get(link.substring(BASE_URL.length())))
                .andExpect(status().isOk())
                .andExpect(content().string("video-bytes"))
                .andExpect(header().string(HttpHeaders.CONTENT_LENGTH, "11"));
    }

    /** Хвостовой слэш в base-url раньше давал «//download/…» — часть прокси такое не отдаёт. */
    @Test
    void linkHasNoDoubleSlash() throws Exception {
        assertThat(completeDownloadAndCaptureLink()).doesNotContain("8080//");
    }

    /** Имя файла с «&» рушило parse_mode=HTML — Telegram отбрасывал всё сообщение. */
    @Test
    void htmlSpecialCharsInFileNameAreEscaped() throws Exception {
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        downloadService.handleDownloadComplete(startDownload(), videoFile);
        verify(messageSender).sendMessageWithKeyboard(eq(CHAT), text.capture(), eq("HTML"), any());

        assertThat(text.getValue()).contains("Ролик &amp; друзья.mp4");
    }

    @Test
    void invalidTokenReturns404() throws Exception {
        mockMvc.perform(get("/download/definitely-not-a-token")).andExpect(status().isNotFound());
    }

    @Test
    void fileWithinTelegramLimitIsAlsoSentToChat() throws Exception {
        downloadService.handleDownloadComplete(startDownload(), videoFile);

        verify(messageSender).sendFile(eq(CHAT), eq(videoFile), any(), eq(null));
    }

    /** Больше 50 МБ Telegram вложением не примет — должна остаться только ссылка. */
    @Test
    void oversizeFileIsDeliveredByLinkOnly() throws Exception {
        ReflectionTestUtils.setField(downloadService, "telegramMaxBytes", 1L);

        String link = completeDownloadAndCaptureLink();

        assertThat(link).contains("/download/");
        verify(messageSender, org.mockito.Mockito.never())
                .sendFile(org.mockito.ArgumentMatchers.anyLong(), any(), any(), any());
    }

    /* ───────── helpers ───────── */

    private String startDownload() {
        downloadService.createDownloadTask(CHAT, "https://youtu.be/dQw4w9WgXcQ", "Аня",
                Bot.processing.MediaKind.VIDEO);
        return downloadService.getActiveDownloads(CHAT).stream()
                .findFirst()
                .map(info -> downloadIdOf(info))
                .orElseThrow();
    }

    /** downloadId наружу не торчит — достаём его из задачи, ушедшей в очередь. */
    private String downloadIdOf(DownloadService.DownloadInfo info) {
        ArgumentCaptor<Bot.processing.ProcessingJob> job =
                ArgumentCaptor.forClass(Bot.processing.ProcessingJob.class);
        verify(jobStore, org.mockito.Mockito.atLeastOnce()).enqueue(job.capture());
        return job.getValue().downloadId();
    }

    private String completeDownloadAndCaptureLink() {
        downloadService.handleDownloadComplete(startDownload(), videoFile);

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(messageSender).sendMessageWithKeyboard(eq(CHAT), text.capture(), eq("HTML"), any());

        Matcher matcher = HREF.matcher(text.getValue());
        assertThat(matcher.find()).as("в сообщении должна быть ссылка").isTrue();
        return matcher.group(1);
    }
}

package Bot.download;

import Bot.processing.JobStore;
import Bot.processing.ProcessingJob;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
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

    /** Задачи, ушедшие в очередь: подменяет собой строки таблицы jobs. */
    private final List<ProcessingJob> enqueued = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        videoFile = Files.writeString(tmp.resolve("Ролик & друзья.mp4"), "video-bytes");

        registry = new DownloadTokenRegistry(inMemoryTokens());
        ReflectionTestUtils.setField(registry, "ttlHours", 24);

        rememberEnqueuedJobs();

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

    /** downloadId наружу не торчит — берём его из задачи, ушедшей в очередь. */
    private String startDownload() {
        downloadService.createDownloadTask(Bot.owner.Owner.telegram(CHAT),
                "https://youtu.be/dQw4w9WgXcQ", Bot.processing.MediaKind.VIDEO);
        return enqueued.get(enqueued.size() - 1).downloadId();
    }

    /**
     * Сведения о загрузке сервис берёт из базы по downloadId, поэтому мок
     * очереди должен помнить поставленные задачи — иначе проверялась бы не
     * та механика, что работает в бою.
     */
    private void rememberEnqueuedJobs() {
        lenient().when(jobStore.enqueue(any())).thenAnswer(call -> {
            ProcessingJob job = call.getArgument(0);
            enqueued.add(job);
            return job;
        });
        lenient().when(jobStore.findDownload(any())).thenAnswer(call -> enqueued.stream()
                .filter(job -> call.getArgument(0).equals(job.downloadId()))
                .findFirst()
                .map(DownloadLinkFlowTest::asDownloadJob));
        lenient().when(jobStore.activeDownloads(any())).thenAnswer(call -> enqueued.stream()
                .filter(job -> job.downloadId() != null && job.owner().equals(call.getArgument(0)))
                .map(DownloadLinkFlowTest::asDownloadJob)
                .toList());
    }

    /**
     * Таблица токенов картой в памяти: этот тест про то, что ссылка из
     * сообщения реально отдаёт файл, а не про хранение — поднимать ради него
     * Postgres незачем. Само хранение проверяет DownloadTokenRegistryTest.
     */
    private DownloadTokenRepository inMemoryTokens() {
        Map<String, DownloadTokenEntity> rows = new HashMap<>();
        DownloadTokenRepository repository = mock(DownloadTokenRepository.class);
        lenient().when(repository.save(any(DownloadTokenEntity.class))).thenAnswer(call -> {
            DownloadTokenEntity entity = call.getArgument(0);
            rows.put(entity.getToken(), entity);
            return entity;
        });
        lenient().when(repository.findById(any()))
                .thenAnswer(call -> Optional.ofNullable(rows.get(call.getArgument(0))));
        return repository;
    }

    private static JobStore.DownloadJob asDownloadJob(ProcessingJob job) {
        return new JobStore.DownloadJob(job.owner(), job.url(), Instant.now());
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

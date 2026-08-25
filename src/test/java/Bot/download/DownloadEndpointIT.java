package Bot.download;

import Bot.processing.JobStore;
import Bot.processing.ProcessingJob;
import Bot.telegram.MessageSender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import Bot.support.PostgresTestContainer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Полный стек: поднятый Spring Boot с настоящим Tomcat.
 *
 * <p>Берём ту самую ссылку, которую бот отправляет пользователю, и качаем по ней
 * файл обычным HTTP-клиентом. Это проверяет не только контроллер, но и то, что
 * маршрут {@code /download/**} не перехвачен обработчиком статики и что базовый
 * URL собирается корректно.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestContainer.class)
@TestPropertySource(properties = {
        "bot.key=111:TEST_TOKEN_NOT_REAL",
        "admin.chat.id=",
        "cleanup.enabled=false",
        // -1 отключает отдельный management-порт: в тестах он не нужен,
        // а 8081 может быть занят рабочим экземпляром бота
        "management.server.port=-1"
})
class DownloadEndpointIT {

    private static final long CHAT = 4242L;
    private static final Pattern HREF = Pattern.compile("href=\"([^\"]+)\"");

    @TempDir static Path tmp;

    @LocalServerPort int port;

    @Autowired DownloadService downloadService;
    @Autowired TestRestTemplate rest;

    @MockBean MessageSender messageSender;

    /** Мок очереди: заодно не даёт воркеру дёрнуть настоящий yt-dlp. */
    @MockBean JobStore jobStore;

    @Test
    void userCanDownloadTheFileByTheLinkBotSent() throws IOException {
        // база должна указывать на реально поднятый порт
        ReflectionTestUtils.setField(downloadService, "downloadBaseUrl", "http://localhost:" + port);
        Path file = Files.writeString(tmp.resolve("clip.mp4"), "hello-from-bot");

        downloadService.handleDownloadComplete(startDownload(), file);

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(messageSender).sendMessageWithKeyboard(eq(CHAT), text.capture(), eq("HTML"), any());

        Matcher matcher = HREF.matcher(text.getValue());
        assertThat(matcher.find()).as("бот должен прислать ссылку").isTrue();
        String link = matcher.group(1);
        assertThat(link).startsWith("http://localhost:" + port + "/download/");

        ResponseEntity<String> response = rest.getForEntity(link, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("hello-from-bot");
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .contains("attachment").contains("clip.mp4");
    }

    /** downloadId наружу не торчит — перехватываем задачу по пути в очередь. */
    private String startDownload() {
        downloadService.createDownloadTask(CHAT, "https://youtu.be/dQw4w9WgXcQ", "Аня",
                Bot.processing.MediaKind.VIDEO);

        ArgumentCaptor<ProcessingJob> job = ArgumentCaptor.forClass(ProcessingJob.class);
        verify(jobStore).enqueue(job.capture());
        ProcessingJob enqueued = job.getValue();

        // Сведения о загрузке сервис берёт из базы по downloadId; мок очереди
        // должен отвечать так же, иначе проверялась бы не та механика
        when(jobStore.findDownload(enqueued.downloadId())).thenReturn(Optional.of(
                new JobStore.DownloadJob(enqueued.owner(), enqueued.url(), Instant.now())));
        return enqueued.downloadId();
    }

    @Test
    void unknownTokenIsNotFound() {
        assertThat(rest.getForEntity("/download/bogus-token", String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}

package Bot.logging;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Проверяет боевой logback.xml: у каждой задачи должен появляться свой файл.
 *
 * <p>При двух параллельных заданиях общий лог перемешивается — по нему нельзя
 * понять, что происходило с конкретной расшифровкой. Тест гоняет настоящую
 * конфигурацию, а не мок, потому что ошибиться легко именно в XML.</p>
 */
class JobLogFileTest {

    private static final Logger log = LoggerFactory.getLogger("Bot.logging.JobLogFileTest");

    private final String jobId = "test-" + UUID.randomUUID();
    private final Path jobLog = Path.of("logs", "jobs", "job-" + jobId + ".log");

    @AfterEach
    void tearDown() throws IOException {
        MDC.clear();
        Files.deleteIfExists(jobLog);
    }

    @Test
    void eachJobGetsItsOwnFileWithItsOwnLines() throws Exception {
        MDC.put("jobId", jobId);
        MDC.put("chatId", "REDACTED_CHAT_ID");

        log.info("Начат этап NEW");
        log.debug("[progress]  42.0%  260/620 MiB");   // детали yt-dlp — только в DEBUG

        String content = awaitContent(jobLog);

        assertThat(content)
                .contains("Начат этап NEW")
                .contains("[progress]  42.0%");
    }

    /** Строки без jobId не должны попадать в файлы задач. */
    @Test
    void linesWithoutJobIdDoNotCreateFiles() throws Exception {
        MDC.clear();
        log.info("Приложение готово: порт=8080");

        assertThat(Path.of("logs", "jobs", "job-none.log")).doesNotExist();
    }

    /** Две задачи не должны писать друг другу в файл. */
    @Test
    void jobsDoNotLeakIntoEachOther() throws Exception {
        String otherId = "test-" + UUID.randomUUID();
        Path otherLog = Path.of("logs", "jobs", "job-" + otherId + ".log");
        try {
            MDC.put("jobId", jobId);
            log.info("строка первой задачи");

            MDC.put("jobId", otherId);
            log.info("строка второй задачи");

            assertThat(awaitContent(jobLog))
                    .contains("строка первой задачи")
                    .doesNotContain("строка второй задачи");
            assertThat(awaitContent(otherLog))
                    .contains("строка второй задачи")
                    .doesNotContain("строка первой задачи");
        } finally {
            Files.deleteIfExists(otherLog);
        }
    }

    /** Аппендер пишет асинхронно относительно вызова — даём файлу появиться. */
    private String awaitContent(Path file) throws Exception {
        for (int i = 0; i < 50 && !Files.exists(file); i++) {
            Thread.sleep(20);
        }
        assertThat(file).as("файл лога задачи").exists();
        return Files.readString(file);
    }
}

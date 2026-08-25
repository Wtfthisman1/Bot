package Bot.processing;


import Bot.download.DownloaderExecutor;
import Bot.telegram.Keyboards;
import Bot.telegram.MessageSender;
import Bot.telegram.TelegramBot;
import Bot.transcription.TranscribeExecutor;
import Bot.download.DownloadService;
import Bot.service.StatusService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@RequiredArgsConstructor
@Slf4j
public class JobWorker {

    /**
     * Пул рабочих потоков обработки задач очереди.
     *
     * <p>Ответственность: несколько потоков параллельно извлекают задачи,
     * выполняют этапы скачивания и транскрипции, отправляют результаты и метят
     * статусы. Связан с {@link JobQueue}, {@link DownloaderExecutor},
     * {@link TranscribeExecutor}, {@link MessageSender}, {@link DownloadService},
     * {@link StatusService}. Ключевые методы: {@code workLoop}, {@code download},
     * {@code transcribe}.</p>
     *
     * <p>Размер пула задаётся свойством {@code worker.pool-size} (по умолчанию 2).
     * {@link JobQueue} использует {@code LinkedBlockingQueue}, чьи {@code take}/
     * {@code offer} потокобезопасны, поэтому несколько потребителей корректно
     * разбирают одну очередь.</p>
     */

    @Value("${worker.pool-size:2}")
    private int poolSize;

    private final JobQueue queue;
    private final DownloaderExecutor downloader;
    private final TranscribeExecutor transcriber;
    private final MessageSender messageSender;
    private final DownloadService downloadService;
    private final StatusService statusService;

    private volatile boolean running = true;
    private ExecutorService pool;

    @PostConstruct
    void start() {
        int size = Math.max(1, poolSize);
        AtomicInteger seq = new AtomicInteger(1);
        pool = Executors.newFixedThreadPool(size, r -> {
            Thread t = new Thread(r, "job-worker-" + seq.getAndIncrement());
            t.setDaemon(true);
            return t;
        });
        for (int i = 0; i < size; i++) {
            pool.submit(this::workLoop);
        }
        log.info("Пул воркеров запущен: потоков={}", size);
    }

    @PreDestroy
    void stop() {
        running = false;
        if (pool != null) {
            // прерываем потоки, заблокированные на queue.take()
            pool.shutdownNow();
        }
        log.info("Пул воркеров остановлен");
    }

    private void workLoop() {
        while (running) {
            try {
                ProcessingJob job = queue.take();   // блокируемся

                // Отмечаем задачу как активную
                statusService.markJobActive(job);

                // jobId/chatId попадают в каждую строку лога этого этапа —
                // так задачу видно насквозь от очереди до результата
                MDC.put("jobId", String.valueOf(job.id()));
                MDC.put(TelegramBot.MDC_CHAT_ID, String.valueOf(job.chatId()));
                long startedAt = System.currentTimeMillis();
                try {
                    log.info("Начат этап {}: jobId={}", job.state(), job.id());
                    switch (job.state()) {
                        case NEW        -> download(job);
                        case DOWNLOADED -> transcribe(job);
                    }
                    log.info("Этап {} завершён за {} мс: jobId={}",
                            job.state(), System.currentTimeMillis() - startedAt, job.id());
                } finally {
                    // Отмечаем задачу как завершенную
                    statusService.markJobCompleted(job.id());
                    MDC.remove("jobId");
                    MDC.remove(TelegramBot.MDC_CHAT_ID);
                }
            } catch (InterruptedException ie) {
                if (!running) {
                    log.info("Воркер прерван при остановке: {}", Thread.currentThread().getName());
                    break;   // нормальный shutdown
                }
                log.warn("Воркер прерван вне процедуры остановки: {}",
                        Thread.currentThread().getName(), ie);
                Thread.currentThread().interrupt();
            } catch (Exception ex) {
                log.error("Ошибка обработки задачи", ex);
            }
        }
        log.info("Поток воркера завершён: {}", Thread.currentThread().getName());
    }

    private void download(ProcessingJob job) {
        try {
            Path file = downloader.download(job.chatId(), job.url(), job.media());
            log.info("Скачано {} (downloadId: {})", file, job.downloadId());
            
            // Проверяем, является ли это задачей загрузки
            if (job.downloadId() != null) {
                log.info("Обрабатываю задачу загрузки с downloadId: {}", job.downloadId());
                downloadService.handleDownloadComplete(job.downloadId(), file);
            } else {
                log.info("Обрабатываю обычную задачу транскрибирования");
                // Обычная задача транскрибирования
                queue.enqueue(job.withFile(file));
            }
        } catch (Exception e) {
            log.error("Ошибка скачивания для URL: {}", job.url(), e);
            
            // Проверяем, является ли это задачей загрузки
            if (job.downloadId() != null) {
                downloadService.handleDownloadError(job.downloadId(), getErrorMessage(job.url(), e));
            } else {
                // Обычная задача транскрибирования
                messageSender.sendMessageWithKeyboard(job.chatId(), getErrorMessage(job.url(), e),
                        null, Keyboards.mainMenu());
            }
        }
    }

    private void transcribe(ProcessingJob job) {
        try {
            Path txt = transcriber.run(job.chatId(), job.filePath());
            log.info("Транскрипция готова {}", txt);
            messageSender.sendTranscript(job.chatId(), txt);
        } catch (Exception e) {
            log.error("Ошибка транскрипции для файла: {}", job.filePath(), e);
            messageSender.sendMessageWithKeyboard(job.chatId(),
                    "❌ Не удалось расшифровать файл. Возможно, он повреждён или в неподдерживаемом формате.",
                    null, Keyboards.mainMenu());
        }
    }

    /**
     * Текст ошибки для пользователя.
     *
     * <p>Разбор вывода yt-dlp живёт в {@link DownloaderExecutor#download}: он уже
     * возвращает готовую формулировку («видео приватное», «нужна авторизация»…).
     * Здесь этот разбор раньше дублировался по вторым признакам — вместо этого
     * просто передаём сообщение дальше.</p>
     */
    private String getErrorMessage(String url, Exception e) {
        String detail = e.getMessage();
        if (detail != null && detail.startsWith("\u274c")) {
            return detail;
        }
        return "\u274c Не удалось обработать ссылку: %s%s".formatted(
                url, detail != null ? "\n\n" + detail : "");
    }
}

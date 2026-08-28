package Bot.processing;

/**
 * Пул рабочих потоков обработки задач.
 *
 * <p>Ответственность: забирать задачи из {@link JobStore}, выполнять этап
 * скачивания или транскрипции, отмечать результат и отдавать его владельцу
 * через {@link JobNotifiers}. Связан с {@link DownloaderExecutor},
 * {@link TranscribeExecutor}, {@link DownloadService}. Ключевые методы:
 * {@code workLoop}, {@code download}, {@code transcribe}.</p>
 *
 * <p>Задачи берутся опросом базы, а не из очереди в памяти: очередь пережила
 * бы перезапуск только на диске. Пустая очередь — обычное состояние, поэтому
 * между опросами воркер спит {@code worker.poll-interval-ms}; задержка в
 * секунду на фоне минут транскрипции незаметна.</p>
 *
 * <p>Размер пула — {@code worker.pool-size} (по умолчанию 2). Одну и ту же
 * задачу двое взять не могут: строка блокируется в базе.</p>
 */
import Bot.config.Profiles;
import Bot.download.DownloadService;
import Bot.download.DownloaderExecutor;
import Bot.notify.JobNotifiers;
import Bot.telegram.TelegramBot;
import Bot.transcription.TranscriptSegments;
import Bot.transcription.TranscribeExecutor;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

@Profile(Profiles.HOME)
@Component
@RequiredArgsConstructor
@Slf4j
public class JobWorker {

    @Value("${worker.pool-size:2}")
    private int poolSize;

    /** Пауза между опросами пустой очереди. */
    @Value("${worker.poll-interval-ms:1000}")
    private long pollIntervalMs;

    private final JobStore jobs;
    private final DownloaderExecutor downloader;
    private final TranscribeExecutor transcriber;
    private final JobNotifiers notifiers;
    private final DownloadService downloadService;
    private final TranscriptSegments transcriptSegments;

    private volatile boolean running = true;
    private ExecutorService pool;

    @PostConstruct
    void start() {
        // Всё, что осталось «в работе», — наследство прошлого запуска:
        // тот воркер уже не вернётся
        jobs.recoverStuck();

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
        log.info("Пул воркеров запущен: потоков={}, опрос каждые {} мс", size, pollIntervalMs);
    }

    @PreDestroy
    void stop() {
        running = false;
        if (pool != null) {
            pool.shutdownNow();   // прерываем потоки, спящие между опросами
        }
        log.info("Пул воркеров остановлен");
    }

    private void workLoop() {
        while (running) {
            try {
                Optional<ProcessingJob> claimed = jobs.claim();
                if (claimed.isEmpty()) {
                    Thread.sleep(pollIntervalMs);
                    continue;
                }
                process(claimed.get());
            } catch (InterruptedException ie) {
                if (!running) {
                    log.info("Воркер прерван при остановке: {}", Thread.currentThread().getName());
                    break;   // нормальный shutdown
                }
                log.warn("Воркер прерван вне процедуры остановки: {}",
                        Thread.currentThread().getName(), ie);
                Thread.currentThread().interrupt();
            } catch (Exception ex) {
                // Сорваться может и сама работа с базой — тогда пауза важнее
                // всего: иначе цикл будет крутиться вхолостую и зальёт лог
                log.error("Ошибка обработки задачи", ex);
                sleepQuietly();
            }
        }
        log.info("Поток воркера завершён: {}", Thread.currentThread().getName());
    }

    /** jobId/chatId попадают в каждую строку лога этапа — задачу видно насквозь. */
    private void process(ProcessingJob job) {
        MDC.put("jobId", job.shortId());
        if (job.owner().isTelegram()) {
            MDC.put(TelegramBot.MDC_CHAT_ID, job.owner().id());
        }
        long startedAt = System.currentTimeMillis();
        try {
            log.info("Начат этап {}: jobId={}", job.stage(), job.shortId());
            switch (job.stage()) {
                case DOWNLOAD   -> download(job);
                case TRANSCRIBE -> transcribe(job);
            }
            log.info("Этап {} завершён за {} мс: jobId={}",
                    job.stage(), System.currentTimeMillis() - startedAt, job.shortId());
        } finally {
            MDC.remove("jobId");
            MDC.remove(TelegramBot.MDC_CHAT_ID);
        }
    }

    private void download(ProcessingJob job) {
        try {
            Path file = downloader.download(job.id(), job.owner(), job.url(), job.media());
            log.info("Скачано {} (downloadId: {})", file, job.downloadId());

            if (job.downloadId() != null) {
                // Задача чистого скачивания: результат уходит ссылкой, расшифровка не нужна
                downloadService.handleDownloadComplete(job.downloadId(), file);
                jobs.complete(job.id(), file);
            } else {
                jobs.moveToTranscribe(job.withFile(file));
            }
        } catch (Exception e) {
            if (wasCancelled(job)) {
                return;
            }
            log.error("Ошибка скачивания для URL: {}", job.url(), e);
            String message = getErrorMessage(job.url(), e);
            jobs.fail(job.id(), message);

            if (job.downloadId() != null) {
                downloadService.handleDownloadError(job.downloadId(), message);
            } else {
                notifiers.failed(job.owner(), message);
            }
        }
    }

    private void transcribe(ProcessingJob job) {
        try {
            Path txt = transcriber.run(job.id(), job.owner(), job.filePath());
            log.info("Транскрипция готова {}", txt);
            jobs.complete(job.id(), txt);
            // Разметка со временем — уже после complete: сегменты нужны сайту,
            // а чат получит расшифровку файлом в любом случае, даже если разбор
            // не задался
            transcriptSegments.importFrom(job.id(), txt);
            notifiers.transcriptReady(job.id(), job.owner(), txt);
        } catch (Exception e) {
            if (wasCancelled(job)) {
                return;
            }
            log.error("Ошибка транскрипции для файла: {}", job.filePath(), e);
            String message = "❌ Не удалось расшифровать файл. "
                    + "Возможно, он повреждён или в неподдерживаемом формате.";
            jobs.fail(job.id(), message);
            notifiers.failed(job.owner(), message);
        }
    }

    /**
     * Не мы сломались — нас остановили.
     *
     * <p>Убитый процесс возвращает ненулевой код, и без этой проверки отмена
     * выглядела бы поломкой: человек, нажавший «остановить», получал бы в ответ
     * «не удалось расшифровать файл», а в журнале копились бы ошибки, которых
     * не было. Состояние читается из базы: пометку туда ставит тот, кто отменил.</p>
     */
    private boolean wasCancelled(ProcessingJob job) {
        if (!jobs.isCancelled(job.id())) {
            return false;
        }
        log.info("Задача остановлена по просьбе владельца: jobId={}", job.shortId());
        return true;
    }

    private void sleepQuietly() {
        try {
            Thread.sleep(pollIntervalMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
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
        if (detail != null && detail.startsWith("❌")) {
            return detail;
        }
        return "❌ Не удалось обработать ссылку: %s%s".formatted(
                url, detail != null ? "\n\n" + detail : "");
    }
}

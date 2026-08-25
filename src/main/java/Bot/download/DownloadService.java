package Bot.download;

/**
 * Сервис управления задачами загрузки по ссылкам.
 *
 * <p>Ответственность: создаёт задачи загрузки, выдаёт рабочую ссылку на готовый
 * файл, уведомляет пользователя об успехе или ошибке. Связан с
 * {@link Bot.processing.JobStore}, {@link DownloadTokenRegistry},
 * {@link MessageSender}, {@link SupportedPlatforms}. Основные методы:
 * {@code createDownloadTask}, {@code handleDownloadComplete},
 * {@code handleDownloadError}.</p>
 *
 * <p><b>Ссылка выдаётся всегда.</b> Раньше файл до 50 МБ уходил вложением, и
 * ссылка формировалась только при сбое отправки — пользователь, нажавший
 * «Скачать», ссылки не получал вовсе. Теперь сообщение со ссылкой отправляется
 * в любом случае, а вложение — дополнительно, когда влезает в лимит Bot API.</p>
 */
import Bot.service.SupportedPlatforms;
import Bot.owner.Owner;
import Bot.processing.JobStore;
import Bot.processing.MediaKind;
import Bot.processing.ProcessingJob;
import Bot.telegram.Keyboards;
import Bot.telegram.MessageSender;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class DownloadService {

    /** Публичный адрес, от которого строится ссылка на скачивание. */
    @Value("${download.base-url:http://localhost:8080}")
    private String downloadBaseUrl;

    /**
     * Максимальный размер файла, который бот отдаёт прямо в чат.
     * Bot API не принимает документы больше 50 МБ.
     */
    @Value("${download.telegram-max-bytes:52428800}")
    private long telegramMaxBytes;

    /** Срок жизни ссылки — только для текста сообщения; сам TTL живёт в реестре. */
    @Value("${download.token.ttl-hours:24}")
    private int linkTtlHours;

    private final JobStore jobStore;
    private final MessageSender messageSender;
    private final DownloadTokenRegistry downloadTokenRegistry;
    private final SupportedPlatforms supportedPlatforms;

    /**
     * Нормализует базовый URL один раз на старте.
     *
     * <p>Хвостовой слэш давал ссылки вида {@code http://host:8080//download/…} —
     * часть прокси такое не маршрутизирует, и ссылка «не работала».</p>
     */
    @PostConstruct
    void init() {
        while (downloadBaseUrl.endsWith("/")) {
            downloadBaseUrl = downloadBaseUrl.substring(0, downloadBaseUrl.length() - 1);
        }
        log.info("Базовый URL для ссылок на скачивание: {}", downloadBaseUrl);
        if (downloadBaseUrl.contains("localhost") || downloadBaseUrl.contains("127.0.0.1")) {
            log.warn("download.base-url указывает на localhost — выданные ссылки откроются только "
                    + "на самом сервере. Задайте DOWNLOAD_BASE_URL с публичным адресом.");
        }
    }

    /**
     * Создаёт задачу на загрузку файла.
     *
     * <p>Проверка платформы продублирована здесь намеренно: это последний рубеж
     * перед yt-dlp, куда произвольный URL попадать не должен (SSRF).</p>
     */
    public void createDownloadTask(Owner owner, String url, MediaKind media) {
        if (!supportedPlatforms.isSupported(url)) {
            log.warn("Отклонён неподдерживаемый URL на скачивание: владелец={}", owner);
            messageSender.sendMessage(owner.telegramChatId(),
                    "❌ Неподдерживаемая или некорректная ссылка.\n\n"
                            + supportedPlatforms.supportedListText());
            return;
        }

        // Внутренний ID отслеживания задачи (не путать с токеном ссылки на скачивание)
        String downloadId = UUID.randomUUID().toString();
        ProcessingJob job = ProcessingJob.newDownload(owner, url, downloadId, media);
        jobStore.enqueue(job);

        log.info("Задача загрузки создана: владелец={}, jobId={}, downloadId={}, media={}",
                owner, job.shortId(), downloadId, media);
    }

    /**
     * Загрузка завершена: регистрируем ссылку, отправляем её и, если файл
     * помещается в лимит Bot API, дополнительно кладём его в чат.
     */
    public void handleDownloadComplete(String downloadId, Path filePath) {
        DownloadInfo info = find(downloadId);
        if (info == null) {
            return;
        }

        try {
            long size = Files.size(filePath);
            log.info("Загрузка завершена: downloadId={}, файл={}, размер={}",
                    downloadId, filePath.getFileName(), formatFileSize(size));

            sendDownloadLink(filePath, info, size);

            if (size <= telegramMaxBytes) {
                // Вложение — приятное дополнение к ссылке. Сбой отправки не критичен:
                // ссылка уже ушла отдельным сообщением
                messageSender.sendFile(info.chatId(), filePath,
                        "📁 " + filePath.getFileName(), null);
            } else {
                log.info("Файл больше лимита Bot API ({}), отправлена только ссылка",
                        formatFileSize(telegramMaxBytes));
            }
        } catch (Exception e) {
            log.error("Ошибка обработки завершённой загрузки: downloadId={}", downloadId, e);
            messageSender.sendMessage(info.chatId(),
                    "❌ Файл скачан, но ссылку сформировать не удалось. Попробуйте ещё раз.");
        }
    }

    /** Обрабатывает ошибку загрузки. */
    public void handleDownloadError(String downloadId, String error) {
        DownloadInfo info = find(downloadId);
        if (info == null) {
            return;
        }

        messageSender.sendMessageWithKeyboard(info.chatId(),
                "❌ Не удалось скачать файл.\n\n" + error, null, Keyboards.mainMenu());
    }

    /**
     * Данные о загрузке из задачи. Пусто означает, что задачи с таким
     * идентификатором в базе нет, — а это уже наша ошибка, не пользователя.
     */
    private DownloadInfo find(String downloadId) {
        return jobStore.findDownload(downloadId)
                .map(DownloadService::toInfo)
                .orElseGet(() -> {
                    log.warn("Не найдена задача загрузки: {}", downloadId);
                    return null;
                });
    }

    private static DownloadInfo toInfo(JobStore.DownloadJob job) {
        return new DownloadInfo(job.owner().telegramChatId(), job.url(), job.startedAt().toEpochMilli());
    }

    /* ───────── helpers ───────── */

    /**
     * Отправляет сообщение со ссылкой на скачивание.
     *
     * <p>Имя файла экранируется: с parse_mode=HTML любой {@code &} или {@code <}
     * в названии ролика приводил к 400 от Telegram, и сообщение со ссылкой
     * не доходило совсем.</p>
     */
    private void sendDownloadLink(Path filePath, DownloadInfo info, long size) {
        String token = downloadTokenRegistry.register(filePath, Owner.telegram(info.chatId()));
        String link = downloadBaseUrl + "/download/" + token;
        log.info("Выдана ссылка на скачивание: chatId={}, файл={}",
                info.chatId(), filePath.getFileName());

        String message = """
                ✅ <b>Файл готов</b>

                📁 %s
                📏 %s

                🔗 <a href="%s">Скачать файл</a>

                ⏰ Ссылка действительна %d ч.
                """.formatted(
                MessageSender.escapeHtml(filePath.getFileName().toString()),
                formatFileSize(size),
                MessageSender.escapeHtml(link),
                linkTtlHours);

        messageSender.sendMessageWithKeyboard(info.chatId(), message.strip(), "HTML",
                Keyboards.mainMenu());
    }

    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    /** Информация о загрузке. */
    public record DownloadInfo(long chatId, String url, long startTime) {}
}

package Bot.processing;

import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;

/**
 * Модель задачи обработки: скачивание и/или транскрипция.
 *
 * <p>Содержит идентификатор, чат, путь к файлу или URL, состояние, вид медиа и,
 * при необходимости, downloadId для задач чистой загрузки. Фабрики:
 * {@code newLink}, {@code newFile}, {@code newDownload}. Переход в состояние
 * DOWNLOADED — через {@code withFile}.</p>
 */
public record ProcessingJob(
        String id,
        long chatId,
        Path filePath,    // null => ещё не скачано
        String url,       // null => локальный файл
        State state,
        String downloadId,// null => обычная задача транскрибирования
        MediaKind media   // что тянуть с площадки
) {
    public enum State { NEW, DOWNLOADED }

    /* приватный canonical-ctor с проверками */
    public ProcessingJob {
        if (state == State.NEW && url == null)
            throw new IllegalArgumentException("NEW job must have url");
        if (state == State.DOWNLOADED && filePath == null)
            throw new IllegalArgumentException("DOWNLOADED job must have filePath");
    }

    /** Ссылка на транскрипцию: звука достаточно, видео качать незачем. */
    public static ProcessingJob newLink(long chatId, String url) {
        Objects.requireNonNull(url, "url");
        return new ProcessingJob(UUID.randomUUID().toString(),
                chatId, null, url, State.NEW, null, MediaKind.AUDIO);
    }

    public static ProcessingJob newFile(long chatId, Path file) {
        Objects.requireNonNull(file, "file");
        return new ProcessingJob(UUID.randomUUID().toString(),
                chatId, file, null, State.DOWNLOADED, null, MediaKind.VIDEO);
    }

    public static ProcessingJob newDownload(long chatId, String url, String downloadId, MediaKind media) {
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(downloadId, "downloadId");
        Objects.requireNonNull(media, "media");
        return new ProcessingJob(UUID.randomUUID().toString(),
                chatId, null, url, State.NEW, downloadId, media);
    }

    public ProcessingJob withFile(Path file) {
        Objects.requireNonNull(file, "file");
        return new ProcessingJob(id, chatId, file, null, State.DOWNLOADED, downloadId, media);
    }

    /** Короткий идентификатор для логов и имён файлов лога. */
    public String shortId() {
        return id.substring(0, 8);
    }
}

package Bot.processing;

/**
 * Модель задачи обработки: скачивание и/или транскрипция.
 *
 * <p>Ответственность: то, что воркер знает о задаче, пока с ней работает.
 * Хранится задача в базе ({@link JobEntity}), а сюда попадает уже разобранной.
 * Фабрики: {@code newLink}, {@code newFile}, {@code newDownload}; переход к
 * расшифровке — {@code withFile}.</p>
 *
 * <p>{@code stage} — это «что с задачей делать», а не «что с ней происходит».
 * Состояние (в очереди, в работе, готово, ошибка) живёт в базе и воркеру
 * неинтересно: он в любом случае занимается тем, что уже забрал.</p>
 */
import Bot.owner.Owner;

import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;

public record ProcessingJob(
        UUID id,
        Owner owner,
        Path filePath,    // null => ещё не скачано
        String url,       // null => локальный файл
        Stage stage,
        String downloadId,// null => обычная задача транскрибирования
        MediaKind media   // что тянуть с площадки
) {
    /** Что с задачей делать дальше. */
    public enum Stage { DOWNLOAD, TRANSCRIBE }

    public ProcessingJob {
        Objects.requireNonNull(owner, "owner");
        if (stage == Stage.DOWNLOAD && url == null)
            throw new IllegalArgumentException("задаче скачивания нужен url");
        if (stage == Stage.TRANSCRIBE && filePath == null)
            throw new IllegalArgumentException("задаче транскрипции нужен файл");
    }

    /** Ссылка на транскрипцию: звука достаточно, видео качать незачем. */
    public static ProcessingJob newLink(Owner owner, String url) {
        Objects.requireNonNull(url, "url");
        return new ProcessingJob(UUID.randomUUID(), owner, null, url, Stage.DOWNLOAD, null, MediaKind.AUDIO);
    }

    public static ProcessingJob newFile(Owner owner, Path file) {
        Objects.requireNonNull(file, "file");
        return new ProcessingJob(UUID.randomUUID(), owner, file, null, Stage.TRANSCRIBE, null, MediaKind.VIDEO);
    }

    public static ProcessingJob newDownload(Owner owner, String url, String downloadId, MediaKind media) {
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(downloadId, "downloadId");
        Objects.requireNonNull(media, "media");
        return new ProcessingJob(UUID.randomUUID(), owner, null, url, Stage.DOWNLOAD, downloadId, media);
    }

    public ProcessingJob withFile(Path file) {
        Objects.requireNonNull(file, "file");
        return new ProcessingJob(id, owner, file, url, Stage.TRANSCRIBE, downloadId, media);
    }

    /** Короткий идентификатор для логов и имён файлов лога. */
    public String shortId() {
        return id.toString().substring(0, 8);
    }
}

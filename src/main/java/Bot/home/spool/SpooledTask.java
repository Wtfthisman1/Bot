package Bot.home.spool;

/**
 * Задача, принятая при спящем доме.
 *
 * <p>Ответственность: описать намерение пользователя так, чтобы его можно было
 * повторить через несколько часов и на другой машине. Поэтому здесь нет ни
 * путей к файлам, ни соединений — только то, что переживёт и перезапуск бота,
 * и выключение дома: владелец, ссылка либо {@code fileId} в Telegram.</p>
 *
 * <p>{@code attempts} считает не любые неудачи, а только ответы дома: молчание
 * выключенной машины попыткой не считается, иначе задача исчерпала бы лимит за
 * одну долгую ночь.</p>
 */
import Bot.home.HomeApi.TelegramFile;
import Bot.owner.Owner;
import Bot.processing.MediaKind;

import java.time.Instant;
import java.util.UUID;

public record SpooledTask(
        UUID id,
        Instant createdAt,
        Kind kind,
        Owner owner,
        String url,
        MediaKind media,
        TelegramFile file,
        int attempts
) {
    /** Что именно повторить, когда дом проснётся. */
    public enum Kind { TRANSCRIBE_LINK, DOWNLOAD_LINK, TELEGRAM_FILE }

    public static SpooledTask transcribeLink(Owner owner, String url) {
        return new SpooledTask(UUID.randomUUID(), Instant.now(),
                Kind.TRANSCRIBE_LINK, owner, url, null, null, 0);
    }

    public static SpooledTask downloadLink(Owner owner, String url, MediaKind media) {
        return new SpooledTask(UUID.randomUUID(), Instant.now(),
                Kind.DOWNLOAD_LINK, owner, url, media, null, 0);
    }

    public static SpooledTask telegramFile(Owner owner, TelegramFile file) {
        return new SpooledTask(UUID.randomUUID(), Instant.now(),
                Kind.TELEGRAM_FILE, owner, null, null, file, 0);
    }

    public SpooledTask afterFailedAttempt() {
        return new SpooledTask(id, createdAt, kind, owner, url, media, file, attempts + 1);
    }

    /** Короткий идентификатор для логов — как у задач в очереди. */
    public String shortId() {
        return id.toString().substring(0, 8);
    }

    /** Что показать пользователю, объясняя судьбу задачи. */
    public String describe() {
        return switch (kind) {
            case TRANSCRIBE_LINK -> "расшифровка " + url;
            case DOWNLOAD_LINK -> "скачивание " + url;
            case TELEGRAM_FILE -> "расшифровка файла " + file.fileName();
        };
    }
}

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
 *
 * <p>{@code createdAt} внутри процесса строго возрастает: по нему выстраивается
 * очередь на отправку, а две задачи, созданные в одно мгновение системных
 * часов, дали бы неопределённый порядок — пользователь прислал бы две ссылки
 * подряд и получил их результаты вперемешку.</p>
 */
import Bot.home.HomeApi.TelegramFile;
import Bot.owner.Owner;
import Bot.processing.MediaKind;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

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

    /** Последний выданный момент — чтобы следующий был строго позже. */
    private static final AtomicReference<Instant> LAST_CREATED =
            new AtomicReference<>(Instant.EPOCH);

    /**
     * Момент создания, гарантированно больший предыдущего.
     *
     * <p>Системные часы этого не обещают: {@code Instant.now()} дважды подряд
     * вполне может вернуть одно и то же значение. Отставание на наносекунды от
     * реального времени роли не играет — момент нужен для порядка, а не для
     * отчётности.</p>
     */
    private static Instant nextMoment() {
        return LAST_CREATED.updateAndGet(previous -> {
            Instant now = Instant.now();
            return now.isAfter(previous) ? now : previous.plusNanos(1);
        });
    }

    public static SpooledTask transcribeLink(Owner owner, String url) {
        return new SpooledTask(UUID.randomUUID(), nextMoment(),
                Kind.TRANSCRIBE_LINK, owner, url, null, null, 0);
    }

    public static SpooledTask downloadLink(Owner owner, String url, MediaKind media) {
        return new SpooledTask(UUID.randomUUID(), nextMoment(),
                Kind.DOWNLOAD_LINK, owner, url, media, null, 0);
    }

    public static SpooledTask telegramFile(Owner owner, TelegramFile file) {
        return new SpooledTask(UUID.randomUUID(), nextMoment(),
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

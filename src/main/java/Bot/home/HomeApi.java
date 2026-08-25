package Bot.home;

/**
 * Всё, что бот просит у домашней машины.
 *
 * <p>Ответственность: единственная граница между приёмом запросов и их
 * выполнением. По одну сторону — Telegram и сайт: они только разбирают, чего
 * хочет пользователь, и отвечают ему текстом. По другую — очередь, база,
 * yt-dlp, Whisper и файлы, которые живут дома и никуда не уезжают.</p>
 *
 * <p>Интерфейс нужен потому, что стороны разъезжаются по машинам: дома его
 * реализует {@link LocalHomeApi} прямым вызовом сервисов, на VPS —
 * {@code HttpHomeApi} запросом по WireGuard. Код бота разницы не видит.</p>
 *
 * <p>Методы ничего не возвращают там, где результат приходит позже: задача
 * ставится в очередь, а расшифровка или ссылка на файл уходят пользователю
 * из дома, когда посчитаются. Возврат есть только у того, что нужно прямо
 * сейчас, — ссылки на форму и сводки по задачам.</p>
 */
import Bot.owner.Owner;
import Bot.processing.MediaKind;
import Bot.transcription.TranscriptFormat;

import java.time.Instant;
import java.util.List;

public interface HomeApi {

    /** Ставит ссылку в очередь на расшифровку. */
    void transcribeLink(Owner owner, String url);

    /** Ставит ссылку в очередь на скачивание; готовый файл уйдёт ссылкой. */
    void downloadLink(Owner owner, String url, MediaKind media);

    /**
     * Ставит в очередь файл, присланный в Telegram.
     *
     * <p>Передаётся не содержимое, а {@code fileId}: дом сам заберёт файл у Bot
     * API. Иначе тот же файл прошёл бы лишний круг — Telegram → VPS → дом.</p>
     *
     * @throws Bot.telegram.FileTooLargeException если Bot API файл не отдаёт
     */
    void transcribeTelegramFile(Owner owner, TelegramFile file) throws Exception;

    /** Одноразовая ссылка на форму загрузки — форму отдаёт дом, файлы там же. */
    String uploadFormLink(Owner owner);

    /** Что у владельца сейчас в работе — для ответа на «Статус». */
    OwnerStatus status(Owner owner);

    /**
     * Отправляет готовую расшифровку в запрошенном формате.
     *
     * <p>Файл лежит дома, поэтому и отправляет его дом. Неизвестный или чужой
     * {@code jobId} — не ошибка вызова: пользователь мог нажать кнопку под очень
     * старым сообщением, и объяснение ему уйдёт тем же путём.</p>
     */
    void sendTranscript(Owner owner, String jobId, TranscriptFormat format);

    /** Файл в Telegram: что забирать и как назвать. */
    record TelegramFile(String fileId, String fileName, Kind kind) {
        public enum Kind { VOICE, AUDIO, VIDEO, DOCUMENT }
    }

    /** Задачи владельца: сколько ждёт очереди, сколько считается, что качается. */
    record OwnerStatus(long queued, long running, List<ActiveDownload> downloads) {

        public static OwnerStatus empty() {
            return new OwnerStatus(0, 0, List.of());
        }

        public long total() {
            return queued + running;
        }
    }

    /** Незавершённая загрузка — строка в сводке «скачивается сейчас». */
    record ActiveDownload(String url, Instant startedAt) {}
}

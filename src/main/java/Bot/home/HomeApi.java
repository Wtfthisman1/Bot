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
 * <p>Результат работы возвращается не отсюда: задача ставится в очередь, а
 * расшифровка или ссылка на файл уходят пользователю из дома, когда
 * посчитаются. Постановка задачи отвечает только тем, началась ли работа
 * прямо сейчас, — см. {@link Acceptance}.</p>
 */
import Bot.owner.Owner;
import Bot.processing.MediaKind;
import Bot.transcription.TranscriptFormat;

import java.time.Instant;
import java.util.List;

public interface HomeApi {

    /** Ставит ссылку в очередь на расшифровку. */
    Acceptance transcribeLink(Owner owner, String url);

    /** Ставит ссылку в очередь на скачивание; готовый файл уйдёт ссылкой. */
    Acceptance downloadLink(Owner owner, String url, MediaKind media);

    /**
     * Ставит в очередь файл, присланный в Telegram.
     *
     * <p>Передаётся не содержимое, а {@code fileId}: дом сам заберёт файл у Bot
     * API. Иначе тот же файл прошёл бы лишний круг — Telegram → VPS → дом.</p>
     *
     * @throws Bot.telegram.FileTooLargeException если Bot API файл не отдаёт
     */
    Acceptance transcribeTelegramFile(Owner owner, TelegramFile file) throws Exception;

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

    /**
     * Началась ли работа прямо сейчас.
     *
     * <p>Домашняя машина включена не круглосуточно, а бот на VPS — всегда.
     * Задача, принятая при спящем доме, лежит в спуле и уходит в работу при
     * пробуждении; пользователю об этом надо сказать честно, иначе привычное
     * «всё запущено» превращается в обман на несколько часов.</p>
     */
    enum Acceptance {
        /** Дом принял задачу, она уже в очереди. */
        STARTED("✅ Всё запущено, ожидайте."),
        /** Дом недоступен: задача лежит в спуле и ждёт его пробуждения. */
        DEFERRED("🌙 Принято. Рабочая машина сейчас спит — работа начнётся, "
                + "как только она проснётся.");

        private final String userMessage;

        Acceptance(String userMessage) {
            this.userMessage = userMessage;
        }

        /** Текст подтверждения: он и есть разница между двумя случаями. */
        public String userMessage() {
            return userMessage;
        }

        public boolean isDeferred() {
            return this == DEFERRED;
        }

        /** Пачка задач отложена, если отложена хоть одна из них. */
        public Acceptance and(Acceptance other) {
            return this == DEFERRED || other == DEFERRED ? DEFERRED : STARTED;
        }
    }

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

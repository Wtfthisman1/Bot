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
import java.util.Optional;

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
     * Заказывает выжимку по готовой расшифровке.
     *
     * <p>Считает дом: там и текст расшифровки, и видеокарта, и языковая модель.
     * Ответа ждать нельзя — пересказ идёт минутами, — поэтому заказ ложится в
     * очередь, а готовое дом сам присылает в тот же чат.</p>
     *
     * <p>Чужую задачу заказать нельзя: {@code jobId} приходит из кнопки, а
     * кнопку можно переслать кому угодно. Владелец сверяется дома, там же, где
     * лежит сама задача.</p>
     *
     * @return причина отказа для человека; пусто — заказ принят
     */
    Optional<String> summarize(Owner owner, String jobId);

    /**
     * Привязывает чат к аккаунту сайта по коду из кабинета.
     *
     * <p>Аккаунты живут в базе, то есть дома, — поэтому решает дом. Пустой
     * ответ означает «код неизвестен, просрочен или уже сработал»: различать
     * эти случаи нельзя, иначе код можно было бы подбирать.</p>
     *
     * @return как называть аккаунт в подтверждении
     */
    Optional<String> linkTelegram(long chatId, String code);

    /**
     * Подтверждает вход на сайт по коду, показанному в браузере.
     *
     * <p>Коды живут дома вместе с аккаунтами, а кнопку нажимают в чате, то есть
     * на стороне бота. Ответ {@code false} означает «код не подошёл» — неважно,
     * неизвестен он, просрочен или уже сработал.</p>
     */
    boolean confirmBotLogin(long chatId, String code, String displayName);

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
                + "как только она проснётся."),
        /**
         * Бесплатные расшифровки этого месяца закончились.
         *
         * <p>Отдельный исход, а не исключение: это не поломка, а обычный ответ
         * «нет». Считает его дом — только у него есть база с историей, — а
         * сказать человеку должен бот, который принял сообщение.</p>
         */
        QUOTA_EXCEEDED("🚫 Бесплатные расшифровки на этот месяц закончились.\n\n"
                + "Новые появятся первого числа. Скачивание файлов работает "
                + "по-прежнему — оно в лимит не входит.");

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

        public boolean isRejected() {
            return this == QUOTA_EXCEEDED;
        }

        /**
         * Итог по пачке ссылок: важнее всего сказать про отказ, затем — про сон
         * машины. «Запущено» — самый слабый исход: он не объясняет ничего,
         * чего человек не ждал бы и так.
         */
        public Acceptance and(Acceptance other) {
            if (this == QUOTA_EXCEEDED || other == QUOTA_EXCEEDED) {
                return QUOTA_EXCEEDED;
            }
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

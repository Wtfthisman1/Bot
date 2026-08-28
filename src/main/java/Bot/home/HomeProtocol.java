package Bot.home;

/**
 * Провод между ботом и домом: адреса, заголовок и тела запросов.
 *
 * <p>Ответственность: держать обе стороны HTTP-разговора в одном файле.
 * {@link HomeApi} остаётся чистым контрактом и о транспорте не знает, а здесь
 * лежит всё, что должно совпасть у {@link HttpHomeApi} и
 * {@link HomeApiController} буква в букву.</p>
 *
 * <p>Путь начинается с {@code /internal}: nginx на VPS наружу пускает только
 * {@code /download/} и {@code /upload/}, поэтому эти адреса физически не
 * видны из интернета — они ходят по WireGuard и защищены общим ключом.</p>
 */
import Bot.insight.InsightKind;
import Bot.owner.Owner;
import Bot.processing.MediaKind;
import Bot.transcription.TranscriptFormat;

public final class HomeProtocol {

    /** Общий корень всех внутренних адресов. */
    public static final String ROOT = "/internal/home";

    /** Заголовок с общим ключом: без него дом не отвечает ничего. */
    public static final String KEY_HEADER = "X-Home-Key";

    public static final String LINK = ROOT + "/jobs/link";
    public static final String DOWNLOAD = ROOT + "/jobs/download";
    public static final String TELEGRAM_FILE = ROOT + "/jobs/telegram-file";
    public static final String UPLOAD_LINK = ROOT + "/upload-links";
    public static final String STATUS = ROOT + "/status";
    public static final String TRANSCRIPT = ROOT + "/transcripts/send";
    public static final String INSIGHT = ROOT + "/transcripts/insight";
    public static final String CANCEL = ROOT + "/jobs/cancel";
    public static final String LINK_ACCOUNT = ROOT + "/accounts/link";
    public static final String BOT_LOGIN = ROOT + "/accounts/bot-login";

    private HomeProtocol() {
    }

    public record LinkRequest(Owner owner, String url) {}

    public record DownloadRequest(Owner owner, String url, MediaKind media) {}

    public record TelegramFileRequest(Owner owner, HomeApi.TelegramFile file) {}

    public record OwnerRequest(Owner owner) {}

    public record TranscriptRequest(Owner owner, String jobId, TranscriptFormat format) {}

    /** Чья задача и какая — её и останавливаем. */
    public record CancelRequest(Owner owner, String jobId) {}

    /** Успели ли остановить: задача могла доделаться, пока человек жал кнопку. */
    public record CancelResponse(boolean cancelled) {}

    /** Чья задача, какая и что с ней делать: пересказать или разобрать по теме. */
    public record InsightRequest(Owner owner, String jobId, InsightKind kind, String topic) {}

    /**
     * Причина отказа, либо {@code null}, если заказ принят.
     *
     * <p>Отказ здесь — обычный ответ, а не ошибка: задача может оказаться
     * чужой, без разметки или уже считаться. Пятисотка на такое заставила бы
     * бота говорить «сломалось» там, где всё работает.</p>
     */
    public record RefusalResponse(String refusal) {}

    /** Код из кабинета и чат, который к нему привязывают. */
    public record LinkAccountRequest(long chatId, String code) {}

    /** Имя аккаунта, либо {@code null}, если код не подошёл. */
    public record LinkAccountResponse(String title) {}

    /** Подтверждение входа на сайт: код из браузера и чат, который его подтвердил. */
    public record BotLoginRequest(long chatId, String code, String displayName) {}

    /** Подошёл ли код. */
    public record BotLoginResponse(boolean confirmed) {}

    /** Ссылка на форму загрузки — единственный ответ, который нужен сразу. */
    public record LinkResponse(String link) {}

    /**
     * Чем закончился приём задачи.
     *
     * <p>Раньше постановка задачи не отвечала ничем: домашняя половина либо
     * приняла её, либо не ответила вовсе. С квотой появился третий исход —
     * «нет, лимит исчерпан», — и его надо донести до бота: только у дома есть
     * база, чтобы это посчитать.</p>
     */
    public record AcceptanceResponse(HomeApi.Acceptance acceptance) {}
}

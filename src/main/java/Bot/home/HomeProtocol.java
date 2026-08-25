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

    private HomeProtocol() {
    }

    public record LinkRequest(Owner owner, String url) {}

    public record DownloadRequest(Owner owner, String url, MediaKind media) {}

    public record TelegramFileRequest(Owner owner, HomeApi.TelegramFile file) {}

    public record OwnerRequest(Owner owner) {}

    public record TranscriptRequest(Owner owner, String jobId, TranscriptFormat format) {}

    /** Ссылка на форму загрузки — единственный ответ, который нужен сразу. */
    public record LinkResponse(String link) {}
}

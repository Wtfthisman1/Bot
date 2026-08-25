package Bot.home;

/**
 * Реализация {@link HomeApi} для процесса, в котором домашней половины нет.
 *
 * <p>Ответственность: те же вызовы, но по HTTP через WireGuard — так работает
 * бот на VPS, где нет ни базы, ни файлов, ни видеокарты. Поднимается в любом
 * процессе без профиля {@code home}: ровно тогда локальной реализации не
 * существует, и звать больше некого.</p>
 *
 * <p>Адрес и ключ обязательны: без них бот способен только здороваться, и
 * молчаливый старт обернулся бы «бот отвечает, но ничего не делает» — гораздо
 * хуже, чем падение с внятной причиной.</p>
 *
 * <p>Разрыв связи отделён от ошибки дома: {@link HomeUnavailableException}
 * означает «домашняя машина спит», и пользователю про это говорят прямо.</p>
 */
import Bot.config.Profiles;
import Bot.home.HomeProtocol.DownloadRequest;
import Bot.home.HomeProtocol.LinkRequest;
import Bot.home.HomeProtocol.LinkResponse;
import Bot.home.HomeProtocol.OwnerRequest;
import Bot.home.HomeProtocol.TelegramFileRequest;
import Bot.home.HomeProtocol.TranscriptRequest;
import Bot.owner.Owner;
import Bot.processing.MediaKind;
import Bot.telegram.FileTooLargeException;
import Bot.transcription.TranscriptFormat;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.function.Supplier;

@Profile("!" + Profiles.HOME)
@Service
@Slf4j
public class HttpHomeApi implements HomeApi {

    /**
     * Спящая машина не отвечает вовсе, поэтому ждать соединения долго незачем:
     * лучше быстро сказать пользователю, что сейчас недоступно.
     */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    /**
     * Ответ приходит после того, как дом заберёт файл у Bot API, — это до
     * десятков секунд на 20 МБ. Меньший таймаут рвал бы нормальную работу.
     */
    private static final Duration READ_TIMEOUT = Duration.ofMinutes(3);

    private final RestClient client;

    public HttpHomeApi(@Value("${home.api.base-url:}") String baseUrl,
                       @Value("${home.api.key:}") String key) {
        if (baseUrl.isBlank() || key.isBlank()) {
            throw new IllegalStateException(
                    "Без профиля 'home' боту нужен адрес домашней машины: задайте HOME_API_URL "
                            + "и HOME_API_KEY (или добавьте профиль 'home', если всё на одной машине)");
        }

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) CONNECT_TIMEOUT.toMillis());
        factory.setReadTimeout((int) READ_TIMEOUT.toMillis());

        this.client = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HomeProtocol.KEY_HEADER, key)
                .requestFactory(factory)
                .build();
        log.info("Задачи уходят на домашнюю машину: {}", baseUrl);
    }

    @Override
    public void transcribeLink(Owner owner, String url) {
        post(HomeProtocol.LINK, new LinkRequest(owner, url));
    }

    @Override
    public void downloadLink(Owner owner, String url, MediaKind media) {
        post(HomeProtocol.DOWNLOAD, new DownloadRequest(owner, url, media));
    }

    @Override
    public void transcribeTelegramFile(Owner owner, TelegramFile file) throws Exception {
        try {
            post(HomeProtocol.TELEGRAM_FILE, new TelegramFileRequest(owner, file));
        } catch (RestClientResponseException e) {
            if (e.getStatusCode() == HttpStatus.PAYLOAD_TOO_LARGE) {
                // Дом уже сходил в Bot API и получил отказ по размеру —
                // для пользователя это то же самое, что отказ на месте
                throw new FileTooLargeException("Bot API не отдал файл: превышен лимит", e);
            }
            throw e;
        }
    }

    @Override
    public String uploadFormLink(Owner owner) {
        LinkResponse response = call(() -> client.post()
                .uri(HomeProtocol.UPLOAD_LINK)
                .body(new OwnerRequest(owner))
                .retrieve()
                .body(LinkResponse.class));
        if (response == null || response.link() == null) {
            throw new IllegalStateException("Дом не вернул ссылку на форму загрузки");
        }
        return response.link();
    }

    @Override
    public OwnerStatus status(Owner owner) {
        OwnerStatus status = call(() -> client.get()
                .uri(builder -> builder.path(HomeProtocol.STATUS)
                        .queryParam("ownerType", owner.type())
                        .queryParam("ownerId", owner.id())
                        .build())
                .retrieve()
                .body(OwnerStatus.class));
        return status == null ? OwnerStatus.empty() : status;
    }

    @Override
    public void sendTranscript(Owner owner, String jobId, TranscriptFormat format) {
        post(HomeProtocol.TRANSCRIPT, new TranscriptRequest(owner, jobId, format));
    }

    /* ───────── helpers ───────── */

    private void post(String path, Object body) {
        call(() -> client.post()
                .uri(path)
                .body(body)
                .retrieve()
                .toBodilessEntity());
    }

    /**
     * Отличает «дома никого» от «дом ответил ошибкой».
     *
     * <p>Первое — обычное дело: машина спит. Второе — наша поломка, и прятать
     * её за тем же текстом нельзя, иначе сломанный воркер будет выглядеть как
     * выключенный компьютер.</p>
     */
    private <T> T call(Supplier<T> request) {
        try {
            return request.get();
        } catch (ResourceAccessException e) {
            throw new HomeUnavailableException("Домашняя машина не отвечает", e);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode() == HttpStatus.FORBIDDEN) {
                log.error("Дом не принял ключ: проверьте HOME_API_KEY с обеих сторон");
            }
            if (e.getStatusCode() == HttpStatus.SERVICE_UNAVAILABLE) {
                throw new HomeUnavailableException("Домашняя машина ещё поднимается", e);
            }
            throw e;
        }
    }
}

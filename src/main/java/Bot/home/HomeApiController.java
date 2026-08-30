package Bot.home;

/**
 * Домашняя сторона провода: принимает запросы бота с VPS.
 *
 * <p>Ответственность: развернуть тело запроса и позвать {@link LocalHomeApi}.
 * Своей логики нет и быть не должно — иначе поведение разъехалось бы с тем,
 * что происходит, когда бот и дом стоят на одной машине и HTTP не участвует.</p>
 *
 * <p>Наружу эти адреса не выставлены: nginx на VPS проксирует только
 * {@code /download/} и {@code /upload/}, а сюда бот ходит по WireGuard. Ключ в
 * заголовке проверяет {@link HomeApiKeyFilter} — он же откажет, если ключ не
 * задан вовсе.</p>
 */
import Bot.config.Profiles;
import Bot.home.HomeProtocol.AcceptanceResponse;
import Bot.home.HomeProtocol.DownloadRequest;
import Bot.home.HomeProtocol.BotLoginRequest;
import Bot.home.HomeProtocol.BotLoginResponse;
import Bot.home.HomeProtocol.LinkAccountRequest;
import Bot.home.HomeProtocol.LinkAccountResponse;
import Bot.home.HomeProtocol.LinkRequest;
import Bot.home.HomeProtocol.LinkResponse;
import Bot.home.HomeProtocol.OwnerRequest;
import Bot.home.HomeProtocol.RefusalResponse;
import Bot.home.HomeProtocol.CancelRequest;
import Bot.home.HomeProtocol.CancelResponse;
import Bot.home.HomeProtocol.InsightRequest;
import Bot.home.HomeProtocol.TelegramFileRequest;
import Bot.home.HomeProtocol.TranscriptRequest;
import Bot.owner.Owner;
import Bot.owner.Owner.OwnerType;
import Bot.telegram.FileTooLargeException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@Profile(Profiles.HOME)
@RestController
@RequestMapping(HomeProtocol.ROOT)
@RequiredArgsConstructor
@Slf4j
public class HomeApiController {

    private final LocalHomeApi home;

    @PostMapping("/jobs/link")
    public AcceptanceResponse transcribeLink(@RequestBody LinkRequest request) {
        return new AcceptanceResponse(home.transcribeLink(request.owner(), request.url()));
    }

    @PostMapping("/jobs/download")
    public AcceptanceResponse downloadLink(@RequestBody DownloadRequest request) {
        return new AcceptanceResponse(home.downloadLink(request.owner(), request.url(), request.media()));
    }

    /**
     * Файл из чата: дом сам забирает его у Bot API по {@code fileId}.
     *
     * <p>Отказ по размеру возвращается кодом 413, а не пятисоткой: на той
     * стороне из него снова получится {@link FileTooLargeException}, и
     * пользователь увидит предложение воспользоваться формой загрузки — то же
     * самое, что он видит без разделения.</p>
     */
    @PostMapping("/jobs/telegram-file")
    public AcceptanceResponse transcribeTelegramFile(@RequestBody TelegramFileRequest request) {
        try {
            return new AcceptanceResponse(
                    home.transcribeTelegramFile(request.owner(), request.file()));
        } catch (FileTooLargeException e) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, e.getMessage(), e);
        } catch (Exception e) {
            log.error("Не удалось забрать файл из Telegram: владелец={}", request.owner(), e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage(), e);
        }
    }

    @PostMapping("/upload-links")
    public LinkResponse uploadFormLink(@RequestBody OwnerRequest request) {
        return new LinkResponse(home.uploadFormLink(request.owner()));
    }

    @GetMapping("/status")
    public HomeApi.OwnerStatus status(@RequestParam OwnerType ownerType,
                                      @RequestParam String ownerId) {
        return home.status(new Owner(ownerType, ownerId));
    }

    @PostMapping("/transcripts/send")
    public void sendTranscript(@RequestBody TranscriptRequest request) {
        home.sendTranscript(request.owner(), request.jobId(), request.format());
    }

    @PostMapping("/transcripts/insight")
    public RefusalResponse orderInsight(@RequestBody InsightRequest request) {
        return new RefusalResponse(home.orderInsight(
                request.owner(), request.jobId(), request.kind(), request.topic()).orElse(null));
    }

    @PostMapping("/jobs/cancel")
    public CancelResponse cancelJob(@RequestBody CancelRequest request) {
        return new CancelResponse(home.cancelJob(request.owner(), request.jobId()));
    }

    @PostMapping("/accounts/bot-login/challenge")
    public HomeProtocol.LoginChallengeResponse loginChallenge(
            @RequestBody HomeProtocol.LoginChallengeRequest request) {
        return new HomeProtocol.LoginChallengeResponse(home.loginChallenge(request.code()));
    }

    @PostMapping("/accounts/bot-login")
    public BotLoginResponse confirmBotLogin(@RequestBody BotLoginRequest request) {
        return new BotLoginResponse(home.confirmBotLogin(
                request.chatId(), request.code(), request.displayName(),
                request.number()).name());
    }

    @PostMapping("/accounts/link")
    public LinkAccountResponse linkTelegram(@RequestBody LinkAccountRequest request) {
        return new LinkAccountResponse(
                home.linkTelegram(request.chatId(), request.code()).orElse(null));
    }
}

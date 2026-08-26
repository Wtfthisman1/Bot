package Bot.home;

/**
 * Домашняя реализация {@link HomeApi}: прямые вызовы сервисов.
 *
 * <p>Ответственность: сложить запрос бота в очередь и вернуть то, что можно
 * ответить сразу. Ничего своего здесь нет — вся работа по-прежнему в
 * {@link JobStore}, {@link DownloadService}, {@link UploadService} и
 * {@link TranscriptDeliveryService}. Класс существует ради границы: пока бот и
 * воркер в одном процессе, эта граница ничего не стоит, а когда бот уедет на
 * VPS, на её месте окажется HTTP-клиент.</p>
 *
 * <p>В chatId владелец разворачивается только там, где на той стороне и правда
 * Telegram: файл забирается у Bot API, расшифровка уходит в чат кнопкой. Всё
 * остальное — хранилище, очередь, форма загрузки — работает с владельцем
 * целиком, потому что у аккаунта сайта чата нет.</p>
 */
import Bot.account.AccountService;
import Bot.account.BotLoginService;
import Bot.account.QuotaService;
import Bot.config.Profiles;
import Bot.download.DownloadService;
import Bot.owner.Owner;
import Bot.processing.JobStore;
import Bot.processing.MediaKind;
import Bot.processing.ProcessingJob;
import Bot.telegram.TelegramFileDownloader;
import Bot.transcription.TranscriptDeliveryService;
import Bot.transcription.TranscriptFormat;
import Bot.upload.UploadService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;

@Profile(Profiles.HOME)
@Service
@RequiredArgsConstructor
@Slf4j
public class LocalHomeApi implements HomeApi {

    private final JobStore jobStore;
    private final QuotaService quotas;
    private final AccountService accounts;
    private final BotLoginService botLogins;
    private final DownloadService downloadService;
    private final UploadService uploadService;
    private final TelegramFileDownloader fileDownloader;
    private final TranscriptDeliveryService transcriptDelivery;

    @Override
    public Acceptance transcribeLink(Owner owner, String url) {
        if (!quotas.allows(owner)) {
            return Acceptance.QUOTA_EXCEEDED;
        }
        jobStore.enqueue(ProcessingJob.newLink(owner, url));
        return Acceptance.STARTED;
    }

    @Override
    public Acceptance downloadLink(Owner owner, String url, MediaKind media) {
        downloadService.createDownloadTask(owner, url, media);
        return Acceptance.STARTED;
    }

    @Override
    public Acceptance transcribeTelegramFile(Owner owner, TelegramFile file) throws Exception {
        // Квота проверяется до похода в Bot API: качать файл, который всё равно
        // не пойдёт в работу, — это минуты канала на пустой отказ
        if (!quotas.allows(owner)) {
            return Acceptance.QUOTA_EXCEEDED;
        }
        long chatId = owner.telegramChatId();
        Path saved = switch (file.kind()) {
            case VOICE -> fileDownloader.downloadVoice(file.fileId(), chatId);
            case AUDIO -> fileDownloader.downloadAudio(file.fileId(), chatId, file.fileName());
            case VIDEO -> fileDownloader.downloadVideo(file.fileId(), chatId, file.fileName());
            case DOCUMENT -> fileDownloader.downloadDocument(file.fileId(), chatId, file.fileName());
        };
        jobStore.enqueue(ProcessingJob.newFile(owner, saved));
        return Acceptance.STARTED;
    }

    @Override
    public String uploadFormLink(Owner owner) {
        return uploadService.generate(owner);
    }

    @Override
    public OwnerStatus status(Owner owner) {
        JobStore.OwnerLoad load = jobStore.load(owner);
        List<ActiveDownload> downloads = jobStore.activeDownloads(owner).stream()
                .map(job -> new ActiveDownload(job.url(), job.startedAt()))
                .toList();
        return new OwnerStatus(load.queued(), load.running(), downloads);
    }

    @Override
    public void sendTranscript(Owner owner, String jobId, TranscriptFormat format) {
        transcriptDelivery.sendFormat(owner.telegramChatId(), jobId, format);
    }

    @Override
    public boolean confirmBotLogin(long chatId, String code, String displayName) {
        return botLogins.confirm(chatId, code, displayName);
    }

    @Override
    public java.util.Optional<String> linkTelegram(long chatId, String code) {
        return accounts.redeemLinkCode(code, chatId).map(AccountService.Account::title);
    }
}

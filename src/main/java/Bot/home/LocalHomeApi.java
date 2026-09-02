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
import Bot.account.LinkAttempts;
import Bot.account.QuotaService;
import Bot.config.Profiles;
import Bot.download.DownloadService;
import Bot.insight.InsightKind;
import Bot.insight.InsightService;
import Bot.owner.Owner;
import Bot.service.SupportedPlatforms;
import Bot.processing.JobAdmission;
import Bot.processing.JobEntity;
import Bot.processing.JobState;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Profile(Profiles.HOME)
@Service
@RequiredArgsConstructor
@Slf4j
public class LocalHomeApi implements HomeApi {

    private final JobStore jobStore;
    private final JobAdmission admission;
    private final QuotaService quotas;
    private final AccountService accounts;
    private final BotLoginService botLogins;
    private final LinkAttempts linkAttempts;
    private final DownloadService downloadService;
    private final UploadService uploadService;
    private final TelegramFileDownloader fileDownloader;
    private final TranscriptDeliveryService transcriptDelivery;
    private final InsightService insights;
    private final SupportedPlatforms supportedPlatforms;
    private final Bot.service.StorageManager storage;

    @Override
    public Acceptance transcribeLink(Owner owner, String url) {
        // Последний рубеж перед yt-dlp, а не удобство: до этой проверки форма
        // загрузки ставила задачу по любому адресу, минуя SupportedPlatforms,
        // и в yt-dlp уезжала произвольная строка
        if (!supportedPlatforms.isSupported(url)) {
            log.warn("Отклонена неподдерживаемая ссылка на расшифровку: владелец={}", owner);
            return Acceptance.UNSUPPORTED;
        }
        // Предел, место и квота проверяются вместе с постановкой в очередь и
        // под замком на аккаунт: врозь между ними помещался чужой запрос
        return answer(admission.admit(ProcessingJob.newLink(owner, url)));
    }

    @Override
    public Acceptance downloadLink(Owner owner, String url, MediaKind media) {
        // Проверка и здесь: раньше метод отвечал «запущено» даже тогда, когда
        // DownloadService ссылку отвергал, и человек получал два разных ответа
        if (!supportedPlatforms.isSupported(url)) {
            log.warn("Отклонена неподдерживаемая ссылка на скачивание: владелец={}", owner);
            return Acceptance.UNSUPPORTED;
        }
        // Скачивание квоты не тратит, поэтому единственное, что его сдерживает, —
        // предел задач и место на диске. Без них один чат забивал и очередь,
        // и диск ссылками на длинные ролики, не нарушая ни одного правила
        return answer(admission.admitDownload(owner,
                () -> downloadService.createDownloadTask(owner, url, media)));
    }

    @Override
    public Acceptance transcribeTelegramFile(Owner owner, TelegramFile file) throws Exception {
        // Дешёвая проверка до похода в Bot API: качать файл, который всё равно
        // не пойдёт в работу, — это минуты канала на пустой отказ
        if (jobStore.tooManyActive(owner)) {
            return Acceptance.TOO_MANY_ACTIVE;
        }
        if (!storage.hasRoom(owner)) {
            return Acceptance.NO_ROOM;
        }
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

        // Решающая проверка — здесь, вместе с постановкой в очередь: пока файл
        // качался, человек мог занять последнюю расшифровку другим запросом.
        // Отказ после скачивания означает, что файл надо убрать за собой:
        // задачи не будет, а место он займёт
        Acceptance verdict = answer(admission.admit(ProcessingJob.newFile(owner, saved)));
        if (verdict != Acceptance.STARTED) {
            deleteQuietly(saved);
        }
        return verdict;
    }

    @Override
    public String uploadFormLink(Owner owner) {
        return uploadService.generate(owner);
    }

    /**
     * Сводка по всем владельцам одного человека.
     *
     * <p>Задачи с сайта записаны на аккаунт, задачи из чата — на чат, и
     * считать только свои значило бы показывать в боте меньше, чем показывает
     * кабинет: история и лимит у них давно общие.</p>
     */
    @Override
    public OwnerStatus status(Owner owner) {
        long queued = 0;
        long running = 0;
        List<ActiveDownload> downloads = new ArrayList<>();
        List<ActiveJob> jobs = new ArrayList<>();

        for (Owner each : accounts.ownersAround(owner)) {
            JobStore.OwnerLoad load = jobStore.load(each);
            queued += load.queued();
            running += load.running();
            jobStore.activeDownloads(each).stream()
                    .map(job -> new ActiveDownload(job.url(), job.startedAt()))
                    .forEach(downloads::add);
            jobStore.unfinished(each).stream().map(LocalHomeApi::toActiveJob).forEach(jobs::add);
        }
        return new OwnerStatus(queued, running, downloads, jobs);
    }

    /**
     * Отмена задачи из чата.
     *
     * <p>Сверяется не только владелец кнопки, но и все владельцы того же
     * человека: задачу могли поставить на сайте, а остановить — из чата.</p>
     */
    @Override
    public boolean cancelJob(Owner owner, String jobId) {
        UUID id;
        try {
            id = UUID.fromString(jobId);
        } catch (IllegalArgumentException e) {
            log.warn("Неразбираемый идентификатор задачи в отмене: владелец={}", owner);
            return false;
        }
        return accounts.ownersAround(owner).stream().anyMatch(each -> jobStore.cancel(id, each));
    }

    @Override
    public void sendTranscript(Owner owner, String jobId, TranscriptFormat format) {
        transcriptDelivery.sendFormat(owner.telegramChatId(), jobId, format);
    }

    /**
     * Заказ обработки из чата.
     *
     * <p>Владелец сверяется по той же расшифровке, что ищут кнопки форматов:
     * нашлась — задача его и уже посчитана, а значит, есть что читать модели.
     * Сверка идёт по всем владельцам человека: чат и аккаунт на сайте — это
     * один человек, и история у них общая.
     * Нечитаемый id — не ошибка вызова, а кнопка из очень старого сообщения,
     * и ответ на неё такой же, как на чужую задачу.</p>
     */
    @Override
    public Optional<String> orderInsight(Owner owner, String jobId, InsightKind kind, String topic) {
        UUID id;
        try {
            id = UUID.fromString(jobId);
        } catch (IllegalArgumentException e) {
            log.warn("Неразбираемый идентификатор задачи в заказе обработки: владелец={}", owner);
            return Optional.of("Эта расшифровка больше недоступна.");
        }

        // Сверяются все владельцы человека, а не только тот, чья кнопка: задачу
        // могли поставить на сайте, а выжимку попросить из чата — ровно так же,
        // как это уже работает у остановки задачи
        if (accounts.ownersAround(owner).stream()
                .allMatch(each -> jobStore.transcriptOf(id, each).isEmpty())) {
            log.info("Обработка не по своей задаче: владелец={}, jobId={}", owner, id);
            return Optional.of("Эта расшифровка больше недоступна.");
        }

        // Доля не задаётся: в чате её выбирать нечем, а умолчание — то же, что
        // предлагает страница, и на живых записях выходит связный пересказ
        Long chatId = owner.isTelegram() ? owner.telegramChatId() : null;
        return insights.order(id, kind, null, topic, chatId);
    }

    @Override
    public Optional<String> loginLink(long chatId, String displayName) {
        return botLogins.issue(chatId, displayName);
    }

    /**
     * Привязка чата к аккаунту по коду из кабинета.
     *
     * <p>Ограничитель стоит здесь, а не внутри {@code AccountService}: это
     * единственная дверь, за которой команда {@code /link} из Telegram, и
     * другой преграды у неё нет — команды приходят не через nginx, где считают
     * попытки входа на сайт.</p>
     *
     * <p>Отказ по перебору выглядит для бота как «код не подошёл»: подсказывать
     * подбирающему, что он упёрся в ограничитель, незачем.</p>
     */
    @Override
    public java.util.Optional<String> linkTelegram(long chatId, String code) {
        if (!linkAttempts.allows(chatId)) {
            log.warn("Привязка отклонена: слишком много неверных кодов (chatId={})", chatId);
            return Optional.empty();
        }

        Optional<String> title = accounts.redeemLinkCode(code, chatId)
                .map(AccountService.Account::title);
        if (title.isPresent()) {
            linkAttempts.succeeded(chatId);
        } else {
            linkAttempts.failed(chatId);
        }
        return title;
    }

    /** Приговор очереди — тем же словарём, которым бот отвечает человеку. */
    private static Acceptance answer(JobAdmission.Verdict verdict) {
        return switch (verdict) {
            case ACCEPTED -> Acceptance.STARTED;
            case TOO_MANY_ACTIVE -> Acceptance.TOO_MANY_ACTIVE;
            case NO_ROOM -> Acceptance.NO_ROOM;
            case QUOTA_EXCEEDED -> Acceptance.QUOTA_EXCEEDED;
        };
    }

    /** Файл, для которого задачи не будет, места занимать не должен. */
    private static void deleteQuietly(Path file) {
        try {
            java.nio.file.Files.deleteIfExists(file);
        } catch (java.io.IOException e) {
            log.warn("Не удалось убрать файл непринятой задачи: {}", file, e);
        }
    }

    /** Строка задачи для сводки: чем она была и что с ней сейчас. */
    private static ActiveJob toActiveJob(JobEntity job) {
        String title = job.getUrl() != null
                ? job.getUrl()
                : job.getFilePath() != null
                        ? Path.of(job.getFilePath()).getFileName().toString()
                        : "Задача " + job.getId().toString().substring(0, 8);
        return new ActiveJob(job.getId().toString(), title,
                job.getState() == JobState.RUNNING, job.getDownloadId() != null);
    }
}

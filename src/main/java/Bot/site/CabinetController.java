package Bot.site;

/**
 * Личный кабинет: задачи, их результаты и остаток квоты.
 *
 * <p>Ответственность: показать историю и принять новую задачу от вошедшего.
 * Своей очереди и своей проверки лимита здесь нет — и то, и другое делает
 * {@link HomeApi}, тот же самый, которым пользуется бот. Иначе сайт и чат
 * разошлись бы в поведении при первой же правке.</p>
 *
 * <p>Файлы отдаются прямо отсюда, без одноразовых токенов: в кабинет уже вошли,
 * и проверка «моя ли это задача» ({@link JobHistory#find}) даёт то же самое,
 * ради чего токен и нужен был в ссылке из чата.</p>
 */
import Bot.account.AccountService;
import Bot.account.IdentityProvider;
import Bot.account.QuotaService;
import Bot.config.Profiles;
import Bot.home.HomeApi;
import Bot.owner.Owner;
import Bot.processing.JobEntity;
import Bot.processing.JobAdmission;
import Bot.processing.JobStore;
import Bot.processing.MediaKind;
import Bot.processing.ProcessingJob;
import Bot.service.MediaFiles;
import Bot.service.StorageManager;
import Bot.service.SupportedPlatforms;
import Bot.transcription.Transcript;
import Bot.transcription.TranscriptFormat;
import Bot.transcription.TranscriptRenderer;
import Bot.transcription.TranscriptSegments;
import Bot.transcription.WordExporter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

@Profile(Profiles.HOME)
@Controller
@RequestMapping("/cabinet")
@RequiredArgsConstructor
@Slf4j
public class CabinetController {

    /** Имя бота без «@»: из него собираются ссылки вида {@code t.me/<бот>}. */
    @Value("${bot.name:}")
    private String botName;

    private final HomeApi home;
    private final JobHistory history;
    private final JobStore jobStore;
    private final JobAdmission admission;
    private final QuotaService quotas;
    private final AccountService accounts;
    private final StorageManager storage;
    private final SupportedPlatforms supportedPlatforms;
    private final WordExporter wordExporter;
    private final TranscriptSegments transcripts;
    private final TranscriptRenderer renderer;
    private final Bot.transcription.TranscriptSearch transcriptSearch;
    private final ActiveSessions activeSessions;
    private final LoginNotice loginNotice;

    @GetMapping
    public String cabinet(@AuthenticationPrincipal AccountPrincipal principal,
                          HttpServletRequest request, Model model) {
        model.addAttribute("account", principal);
        model.addAttribute("quota", quotas.of(principal.accountId()));
        model.addAttribute("jobs", history.of(principal.accountId()));
        model.addAttribute("supported", supportedPlatforms.supportedListText());
        model.addAttribute("botName", botName);
        // Тому, кто вошёл через Telegram, привязывать нечего: чат и аккаунт
        // и так одно целое. Показывать ему кнопку — сбивать с толку
        model.addAttribute("telegramLinked",
                accounts.providersOf(principal.accountId()).contains(IdentityProvider.TELEGRAM));

        // Пароль: есть ли он вообще, есть ли куда его прикладывать (почта) и
        // надо ли спрашивать старый. Последнее зависит от двери, которой вошли:
        // вошедшему паролем без старого менять нельзя, остальным — можно, и это
        // и есть восстановление вместо письма, которого мы слать не умеем
        boolean hasEmail = accounts.findById(principal.accountId())
                .map(account -> account.email() != null && !account.email().isBlank())
                .orElse(false);
        model.addAttribute("passwordEmail", hasEmail);
        model.addAttribute("passwordSet", accounts.hasPassword(principal.accountId()));
        model.addAttribute("passwordAsksCurrent", SessionLogin.doorOf(request)
                .map(door -> door == SessionLogin.Door.PASSWORD)
                .orElse(true));
        return "cabinet";
    }

    /** Ссылка из формы кабинета — тот же путь, что и ссылка, присланная боту. */
    @PostMapping("/tasks")
    public String submitLink(@AuthenticationPrincipal AccountPrincipal principal,
                             @RequestParam String url,
                             @RequestParam(defaultValue = "transcribe") String action,
                             @RequestParam(defaultValue = "VIDEO") MediaKind media,
                             RedirectAttributes redirect) {
        Owner owner = Owner.account(principal.accountId().toString());
        String link = url == null ? "" : url.trim();

        if (!supportedPlatforms.isSupported(link)) {
            redirect.addFlashAttribute("error",
                    "С этой ссылкой я работать не умею. " + supportedPlatforms.supportedListText());
            return "redirect:/cabinet";
        }

        HomeApi.Acceptance acceptance = "download".equals(action)
                ? home.downloadLink(owner, link, media)
                : home.transcribeLink(owner, link);

        redirect.addFlashAttribute(acceptance.isRejected() ? "error" : "message",
                acceptance.userMessage());
        return "redirect:/cabinet";
    }

    /** Файл из формы кабинета кладётся в хранилище аккаунта и идёт в очередь. */
    @PostMapping("/upload")
    public String uploadFile(@AuthenticationPrincipal AccountPrincipal principal,
                             @RequestParam("file") MultipartFile file,
                             RedirectAttributes redirect) throws IOException {
        Owner owner = Owner.account(principal.accountId().toString());

        if (file == null || file.isEmpty()) {
            redirect.addFlashAttribute("error", "Файл не выбран.");
            return "redirect:/cabinet";
        }
        // В чате документы фильтруются по расширению, а здесь не фильтровались
        // вовсе: на диск ложился любой файл до 2,5 ГБ, и задача уходила в
        // очередь, чтобы упасть на ffmpeg через полчаса
        if (!MediaFiles.isMedia(file.getOriginalFilename())) {
            redirect.addFlashAttribute("error",
                    "Такой файл расшифровать не получится. " + MediaFiles.supportedListText());
            return "redirect:/cabinet";
        }
        // Предел задач, место и квота проверяются вместе с постановкой в
        // очередь и под замком на аккаунт: врозь между «посчитал» и «поставил»
        // помещалась соседняя вкладка того же человека. Файл при этом
        // сохраняется до приговора и при отказе убирается: место считается по
        // тому, что лежит на диске, а лежит там уже и он
        Path saved = storage.uploadedPath(owner, file.getOriginalFilename());
        Files.createDirectories(saved.getParent());
        file.transferTo(saved);

        JobAdmission.Verdict verdict = admission.admit(ProcessingJob.newFile(owner, saved));
        if (!verdict.accepted()) {
            Files.deleteIfExists(saved);
            redirect.addFlashAttribute("error", switch (verdict) {
                case NO_ROOM -> "Не хватает места: на аккаунт отведено "
                        + storage.maxBytesPerOwner() / 1073741824 + " ГБ. "
                        + "Старые записи уносит ночная чистка через две недели.";
                case TOO_MANY_ACTIVE -> HomeApi.Acceptance.TOO_MANY_ACTIVE.userMessage();
                default -> HomeApi.Acceptance.QUOTA_EXCEEDED.userMessage();
            });
            return "redirect:/cabinet";
        }

        log.info("Файл принят из кабинета: аккаунт={}, имя='{}', размер={} байт",
                principal.accountId(), file.getOriginalFilename(), file.getSize());
        redirect.addFlashAttribute("message", "✅ Файл принят, задача в очереди.");
        return "redirect:/cabinet";
    }

    /**
     * Остановка задачи, которая ещё идёт.
     *
     * <p>Кнопка есть и в боте, и здесь, потому что задачу ставят в одном месте,
     * а спохватываются в другом: запись на час занимает видеокарту надолго, и
     * ждать её конца ради ошибки в ссылке незачем.</p>
     *
     * <p>Проверка «моя ли задача» — та же, что у остальных страниц кабинета:
     * {@code history.find} чужую не отдаст.</p>
     */
    @PostMapping("/jobs/{jobId}/cancel")
    public String cancelJob(@AuthenticationPrincipal AccountPrincipal principal,
                            @PathVariable String jobId,
                            RedirectAttributes redirect) {
        JobEntity job = parseId(jobId)
                .flatMap(id -> history.find(principal.accountId(), id))
                .orElse(null);
        if (job == null) {
            redirect.addFlashAttribute("error", "Такой задачи нет.");
            return "redirect:/cabinet";
        }

        boolean stopped = jobStore.cancel(job.getId(), job.owner());
        redirect.addFlashAttribute(stopped ? "message" : "error", stopped
                ? "⛔ Остановлено. Лимит расшифровок эта задача не потратила."
                : "Эту задачу уже не остановить — она успела доделаться или снята раньше.");
        return "redirect:/cabinet";
    }

    /**
     * Результат задачи: расшифровка в выбранном формате или сам скачанный файл.
     *
     * <p>{@code media} — это исходник (скачанное видео или присланная запись),
     * остальные коды — форматы расшифровки из {@link TranscriptFormat}.</p>
     */
    @GetMapping("/files/{jobId}/{format}")
    public ResponseEntity<Resource> file(@AuthenticationPrincipal AccountPrincipal principal,
                                         @PathVariable String jobId,
                                         @PathVariable String format) throws IOException {
        JobEntity job = parseId(jobId)
                .flatMap(id -> history.find(principal.accountId(), id))
                .orElse(null);
        if (job == null) {
            return ResponseEntity.notFound().build();
        }

        Path file = resolve(job, format);
        if (file == null || !Files.isRegularFile(file)) {
            // Ночная чистка уносит файлы старше двух недель — для человека это
            // не ошибка, а «уже нельзя», и отвечать надо тем же, чем на чужой номер
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.getFileName().toString(), StandardCharsets.UTF_8)
                        .build().toString())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(Files.size(file))
                .body(new FileSystemResource(file));
    }

    /**
     * Поиск по своим расшифровкам.
     *
     * <p>Ищется место в записи, а не запись: попадание ведёт на страницу
     * расшифровки и сразу перематывает плеер к нужной секунде.</p>
     */
    @GetMapping("/search")
    public String search(@AuthenticationPrincipal AccountPrincipal principal,
                         @RequestParam(required = false) String q, Model model) {
        Map<java.util.UUID, String> titles = history.titlesOf(principal.accountId());

        model.addAttribute("account", principal);
        model.addAttribute("q", q);
        model.addAttribute("titles", titles);
        model.addAttribute("hits", transcriptSearch.find(titles.keySet(), q));
        return "search";
    }

    /**
     * Закрывает все остальные сессии этого аккаунта.
     *
     * <p>Ответ на сообщение «в ваш аккаунт вошли», которое бот присылает после
     * каждого входа по ссылке. Единственный способ увести аккаунт через бота —
     * уговорить человека переслать свою ссылку; кнопка здесь делает такой
     * увод обратимым, пока сессия жива.</p>
     */
    @PostMapping("/sessions/close")
    public String closeSessions(@AuthenticationPrincipal AccountPrincipal principal,
                                HttpServletRequest request, RedirectAttributes redirect) {
        HttpSession current = request.getSession(false);
        int closed = activeSessions.closeOthers(principal.accountId(),
                current == null ? "" : current.getId());
        redirect.addFlashAttribute("message", closed == 0
                ? "Больше нигде вход не выполнен — закрывать нечего."
                : "Готово. Закрыто других сеансов: " + closed + ".");
        return "redirect:/cabinet";
    }

    /**
     * Смена пароля — она же его восстановление.
     *
     * <p>Сброса по письму нет: почтовой службы у нас нет вовсе, и отправить
     * ссылку «я забыл пароль» некуда. Вместо этого работает вторая дверь —
     * бот, Telegram или Google: войдя ими, человек доказал, что аккаунт его, и
     * задаёт новый пароль, не зная старого. Кто вошёл паролем, повторяет его:
     * иначе чужая открытая вкладка меняла бы пароль молча.</p>
     *
     * <p>После смены остальные сеансы закрываются, а в привязанный чат уходит
     * сообщение. Смена пароля — обычный первый ход того, кто увёл аккаунт;
     * молчать о ней означало бы оставить человека без единственного признака,
     * по которому он это заметит.</p>
     */
    @PostMapping("/password")
    public String changePassword(@AuthenticationPrincipal AccountPrincipal principal,
                                 @RequestParam(required = false) String currentPassword,
                                 @RequestParam String newPassword,
                                 HttpServletRequest request, RedirectAttributes redirect) {
        // Неизвестную дверь толкуем строго: сессии, заведённые до появления
        // отметки, не должны давать смену пароля без старого
        boolean mustKnowCurrent = SessionLogin.doorOf(request)
                .map(door -> door == SessionLogin.Door.PASSWORD)
                .orElse(true);
        try {
            accounts.changePassword(principal.accountId(), currentPassword,
                    newPassword, mustKnowCurrent);
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
            return "redirect:/cabinet";
        }

        HttpSession current = request.getSession(false);
        activeSessions.closeOthers(principal.accountId(), current == null ? "" : current.getId());
        loginNotice.passwordChanged(accounts.ownersOf(principal.accountId()), request);

        redirect.addFlashAttribute("message",
                "Пароль изменён. Остальные сеансы закрыты.");
        return "redirect:/cabinet";
    }

    /** Код привязки: его человек присылает боту, чтобы чат стал этим аккаунтом. */
    @PostMapping("/link-code")
    public String linkCode(@AuthenticationPrincipal AccountPrincipal principal,
                           RedirectAttributes redirect) {
        String code = accounts.issueLinkCode(principal.accountId());
        redirect.addFlashAttribute("linkCode", code);
        return "redirect:/cabinet";
    }

    /* ───────── helpers ───────── */

    private Path resolve(JobEntity job, String format) throws IOException {
        if ("media".equals(format)) {
            return job.getFilePath() == null ? null : Path.of(job.getFilePath());
        }
        if (job.getTranscriptPath() == null) {
            return null;
        }

        Optional<TranscriptFormat> requested = TranscriptFormat.fromCode(format);
        if (requested.isEmpty()) {
            return null;
        }

        // Правки человека живут в базе, а файлы Whisper написаны один раз и о
        // них не знают: пока сегменты есть, файл собирается заново из них.
        // Иначе исправленное в редакторе не попадало бы в скачанное — а именно
        // за этим редактор и заводили
        Transcript transcript = transcripts.of(job.getId());
        if (!transcript.isEmpty()) {
            return rendered(job, transcript, requested.get());
        }

        Path txt = Path.of(job.getTranscriptPath());
        return requested.get() == TranscriptFormat.DOCX
                ? wordExporter.export(txt)
                : requested.get().fileFor(txt);
    }

    /**
     * Собирает файл из сегментов во временном каталоге.
     *
     * <p>Не рядом с исходной расшифровкой: та осталась такой, какой её выдал
     * Whisper, и переписывать её правками — значит потерять оригинал. Имя файлу
     * даётся прежнее, потому что его увидит человек в загрузках.</p>
     */
    private Path rendered(JobEntity job, Transcript transcript, TranscriptFormat format)
            throws IOException {
        String stem = stemOf(Path.of(job.getTranscriptPath()));
        Path dir = Files.createTempDirectory("transcript-");
        dir.toFile().deleteOnExit();

        Path txt = dir.resolve(stem + TranscriptFormat.TXT.extension());
        Files.writeString(txt, renderer.asText(transcript));
        txt.toFile().deleteOnExit();

        if (format == TranscriptFormat.TXT) {
            return txt;
        }
        if (format == TranscriptFormat.DOCX) {
            Path docx = wordExporter.export(txt);
            docx.toFile().deleteOnExit();
            return docx;
        }

        Path out = dir.resolve(stem + format.extension());
        Files.writeString(out, renderer.render(transcript, format));
        out.toFile().deleteOnExit();
        return out;
    }

    private static String stemOf(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private Optional<java.util.UUID> parseId(String value) {
        try {
            return Optional.of(java.util.UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}

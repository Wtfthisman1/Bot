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
import Bot.processing.JobStore;
import Bot.processing.MediaKind;
import Bot.processing.ProcessingJob;
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
    private final QuotaService quotas;
    private final AccountService accounts;
    private final StorageManager storage;
    private final SupportedPlatforms supportedPlatforms;
    private final WordExporter wordExporter;
    private final TranscriptSegments transcripts;
    private final TranscriptRenderer renderer;
    private final Bot.transcription.TranscriptSearch transcriptSearch;

    @GetMapping
    public String cabinet(@AuthenticationPrincipal AccountPrincipal principal, Model model) {
        model.addAttribute("account", principal);
        model.addAttribute("quota", quotas.of(principal.accountId()));
        model.addAttribute("jobs", history.of(principal.accountId()));
        model.addAttribute("supported", supportedPlatforms.supportedListText());
        model.addAttribute("botName", botName);
        // Тому, кто вошёл через Telegram, привязывать нечего: чат и аккаунт
        // и так одно целое. Показывать ему кнопку — сбивать с толку
        model.addAttribute("telegramLinked",
                accounts.providersOf(principal.accountId()).contains(IdentityProvider.TELEGRAM));
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
        if (!quotas.allows(owner)) {
            redirect.addFlashAttribute("error", HomeApi.Acceptance.QUOTA_EXCEEDED.userMessage());
            return "redirect:/cabinet";
        }

        Path saved = storage.uploadedPath(owner, file.getOriginalFilename());
        Files.createDirectories(saved.getParent());
        file.transferTo(saved);
        jobStore.enqueue(ProcessingJob.newFile(owner, saved));

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

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
import Bot.transcription.TranscriptFormat;
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

        Path txt = Path.of(job.getTranscriptPath());
        Optional<TranscriptFormat> requested = TranscriptFormat.fromCode(format);
        if (requested.isEmpty()) {
            return null;
        }
        return requested.get() == TranscriptFormat.DOCX
                ? wordExporter.export(txt)
                : requested.get().fileFor(txt);
    }

    private Optional<java.util.UUID> parseId(String value) {
        try {
            return Optional.of(java.util.UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}

package Bot.upload;

import Bot.config.Profiles;
import Bot.owner.Owner;
import Bot.processing.JobStore;
import Bot.processing.ProcessingJob;
import Bot.service.StorageManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;

/**
 * Контроллер формы загрузки: файлы и ссылки → задачи в очередь.
 *
 * <p>Ответственность: отдаёт HTML-форму и принимает multipart-запросы до 5
 * файлов и 5 ссылок. Валидирует одноразовый токен, сохраняет файлы и ставит
 * задачи в {@link JobStore}. URL формы: <b>/upload/{token}</b>.</p>
 */
@Profile(Profiles.HOME)
@RestController
@RequestMapping("/upload")
@RequiredArgsConstructor
@Slf4j
public class UploadController {

    private static final int MAX_SLOTS = 5;

    private final UploadService  uploadService;
    private final JobStore       jobStore;
    private final StorageManager storageManager;


    /* ---------- отдаём форму ---------- */
    @GetMapping(value = "/{token}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<Resource> uploadForm(@PathVariable String token) {
        log.info("Открыта форма загрузки по токену");
        // upload.html лежит в src/main/resources/static/
        Resource html = new ClassPathResource("static/upload.html");
        // Если хотите проверять/блокировать токен до показа формы — сделайте это здесь.
        return ResponseEntity.ok(html);
    }

    @PostMapping(value = "/{token}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<String> handleUpload(@PathVariable String token,
                                               @RequestParam(value = "file", required = false)
                                               MultipartFile[] files,
                                               @RequestParam(value = "url",  required = false)
                                               String[] urls) throws Exception {

        /* ---------- 0. проверяем токен ---------- */
        Long chatId = uploadService.consume(token);
        if (chatId == null) {
            log.warn("Загрузка отклонена: недействительный или просроченный токен");
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body("Недействительный или просроченный токен.");
        }

        int fileCount = files != null ? files.length : 0;
        int urlCount  = urls  != null ? urls.length  : 0;

        /* ---------- 1. валидация ---------- */
        if (fileCount == 0 && urlCount == 0) {
            log.warn("Пустая загрузка: chatId={}", chatId);
            return ResponseEntity.badRequest()
                    .body("Нужно выбрать хотя бы один файл или указать хотя бы одну ссылку.");
        }

        if (fileCount > MAX_SLOTS || urlCount > MAX_SLOTS) {
            log.warn("Превышен лимит слотов: chatId={}, файлов={}, ссылок={}",
                    chatId, fileCount, urlCount);
            return ResponseEntity.badRequest()
                    .body("Максимум " + MAX_SLOTS + " файлов и " + MAX_SLOTS + " ссылок за раз.");
        }

        /* ---------- 2. файлы ---------- */
        if (files != null) {
            for (MultipartFile f : files) {
                if (f == null || f.isEmpty()) continue;
                Path dst = storageManager.uploadedPath(chatId, f.getOriginalFilename());
                log.info("Принят файл через форму: chatId={}, имя='{}', размер={} байт",
                        chatId, f.getOriginalFilename(), f.getSize());
                f.transferTo(dst);                                     // сохраняем
                jobStore.enqueue(ProcessingJob.newFile(Owner.telegram(chatId), dst));  // сразу в очередь
            }
        }

        /* ---------- 3. ссылки ---------- */
        if (urls != null) {
            for (String u : urls) {
                if (u == null || u.isBlank()) continue;
                jobStore.enqueue(ProcessingJob.newLink(Owner.telegram(chatId), u.trim()));
            }
        }

        log.info("Принято от chat {}: {} файлов, {} ссылок", chatId, fileCount, urlCount);
        return ResponseEntity.ok("Принято! Задачи поставлены в очередь.");
    }
}

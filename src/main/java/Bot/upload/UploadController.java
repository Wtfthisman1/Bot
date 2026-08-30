package Bot.upload;

import Bot.account.QuotaService;
import Bot.config.Profiles;
import Bot.home.HomeApi;
import Bot.owner.Owner;
import Bot.processing.JobStore;
import Bot.processing.ProcessingJob;
import Bot.service.StorageManager;
import Bot.service.SupportedPlatforms;
import Bot.telegram.Keyboards;
import Bot.telegram.MessageSender;
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
    private final QuotaService   quotas;
    private final MessageSender  messageSender;
    private final SupportedPlatforms supportedPlatforms;


    /* ---------- отдаём форму ---------- */

    /**
     * Форма — или объяснение, почему её больше нет.
     *
     * <p>Токен проверяется до показа: ссылка одноразовая, и живая на вид форма
     * провоцирует выбрать файл и дождаться конца загрузки, чтобы получить отказ
     * в самом конце. Токен здесь только проверяется, но не гасится — иначе
     * форму нельзя было бы даже открыть.</p>
     */
    @GetMapping(value = "/{token}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<Resource> uploadForm(@PathVariable String token) {
        if (!uploadService.isLive(token)) {
            log.info("Открыта недействительная ссылка на форму загрузки");
            return ResponseEntity.status(HttpStatus.GONE)
                    .body(new ClassPathResource("static/upload-expired.html"));
        }

        log.info("Открыта форма загрузки по токену");
        // upload.html лежит в src/main/resources/static/
        return ResponseEntity.ok(new ClassPathResource("static/upload.html"));
    }

    @PostMapping(value = "/{token}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<String> handleUpload(@PathVariable String token,
                                               @RequestParam(value = "file", required = false)
                                               MultipartFile[] files,
                                               @RequestParam(value = "url",  required = false)
                                               String[] urls) throws Exception {

        /* ---------- 0. проверяем токен ---------- */
        Owner owner = uploadService.consume(token);
        if (owner == null) {
            log.warn("Загрузка отклонена: недействительный или просроченный токен");
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body("Недействительный или просроченный токен.");
        }

        int fileCount = files != null ? files.length : 0;
        int urlCount  = urls  != null ? urls.length  : 0;

        /* ---------- 1. валидация ---------- */
        if (fileCount == 0 && urlCount == 0) {
            log.warn("Пустая загрузка: владелец={}", owner);
            return ResponseEntity.badRequest()
                    .body("Нужно выбрать хотя бы один файл или указать хотя бы одну ссылку.");
        }

        // Форма — такая же расшифровка, как ссылка в чате, и лимит у неё общий.
        // Без этой проверки достаточно было бы прислать файл формой, чтобы
        // обойти квоту целиком
        if (!quotas.allows(owner)) {
            log.info("Загрузка отклонена по квоте: владелец={}", owner);
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(HomeApi.Acceptance.QUOTA_EXCEEDED.userMessage());
        }

        if (fileCount > MAX_SLOTS || urlCount > MAX_SLOTS) {
            log.warn("Превышен лимит слотов: владелец={}, файлов={}, ссылок={}",
                    owner, fileCount, urlCount);
            return ResponseEntity.badRequest()
                    .body("Максимум " + MAX_SLOTS + " файлов и " + MAX_SLOTS + " ссылок за раз.");
        }

        int acceptedFiles = 0;
        int acceptedUrls = 0;
        int overQuota = 0;
        int unsupported = 0;

        /* ---------- 2. файлы ---------- */
        if (files != null) {
            for (MultipartFile f : files) {
                if (f == null || f.isEmpty()) continue;
                // Квота спрашивается на каждую задачу, а не один раз на запрос:
                // одной проверки хватало на пять файлов и пять ссылок разом,
                // то есть лимит в три расшифровки давал десять
                if (!quotas.allows(owner)) {
                    overQuota++;
                    continue;
                }
                Path dst = storageManager.uploadedPath(owner, f.getOriginalFilename());
                log.info("Принят файл через форму: владелец={}, имя='{}', размер={} байт",
                        owner, f.getOriginalFilename(), f.getSize());
                f.transferTo(dst);                                     // сохраняем
                jobStore.enqueue(ProcessingJob.newFile(owner, dst));   // сразу в очередь
                acceptedFiles++;
            }
        }

        /* ---------- 3. ссылки ---------- */
        if (urls != null) {
            for (String u : urls) {
                if (u == null || u.isBlank()) continue;
                String link = u.trim();
                // Проверка площадки: без неё форма ставила задачу по любому
                // адресу, и в yt-dlp уезжала произвольная строка — и как цель
                // запроса с домашней машины, и как аргумент командной строки
                if (!supportedPlatforms.isSupported(link)) {
                    log.warn("Отклонена неподдерживаемая ссылка из формы: владелец={}", owner);
                    unsupported++;
                    continue;
                }
                if (!quotas.allows(owner)) {
                    overQuota++;
                    continue;
                }
                jobStore.enqueue(ProcessingJob.newLink(owner, link));
                acceptedUrls++;
            }
        }

        log.info("Принято от {}: {} файлов, {} ссылок; отклонено: по квоте {}, по площадке {}",
                owner, acceptedFiles, acceptedUrls, overQuota, unsupported);

        if (acceptedFiles == 0 && acceptedUrls == 0) {
            // Код отражает причину: неподдерживаемая ссылка — это ошибка в
            // запросе, а исчерпанный лимит — «слишком часто»
            if (unsupported > 0 && overQuota == 0) {
                return ResponseEntity.badRequest()
                        .body(HomeApi.Acceptance.UNSUPPORTED.userMessage() + "\n\n"
                                + supportedPlatforms.supportedListText());
            }
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(HomeApi.Acceptance.QUOTA_EXCEEDED.userMessage());
        }

        tellOwner(owner, acceptedFiles, acceptedUrls);
        return ResponseEntity.ok(answer(overQuota, unsupported));
    }

    /** Честный ответ формы: что взяли и что не взяли. */
    private static String answer(int overQuota, int unsupported) {
        StringBuilder text = new StringBuilder("Принято! Задачи поставлены в очередь.");
        if (overQuota > 0) {
            text.append(" Не влезло в лимит: ").append(overQuota).append('.');
        }
        if (unsupported > 0) {
            text.append(" Отклонено ссылок с неподдерживаемых площадок: ")
                    .append(unsupported).append('.');
        }
        return text.toString();
    }

    /**
     * Говорит в чат, что файл принят.
     *
     * <p>Форму открывают из бота и возвращаются в него же; без этого сообщения
     * человек, закрыв вкладку, не имеет никаких признаков, что работа пошла, —
     * а до готовой расшифровки могут пройти минуты.</p>
     *
     * <p>У аккаунта сайта чата нет: ему о принятом говорит сама страница.</p>
     */
    private void tellOwner(Owner owner, int fileCount, int urlCount) {
        if (!owner.isTelegram()) {
            return;
        }
        StringBuilder text = new StringBuilder("📥 Принято через форму: ");
        if (fileCount > 0) {
            text.append("файлов — ").append(fileCount);
        }
        if (urlCount > 0) {
            text.append(fileCount > 0 ? ", " : "").append("ссылок — ").append(urlCount);
        }
        text.append(".\n\nЗадачи в очереди, расшифровка придёт сюда. "
                + "Ссылка на форму больше не действует — за новой нажмите «Загрузить файлы».");
        messageSender.sendMessageWithKeyboard(owner.telegramChatId(), text.toString(),
                null, Keyboards.mainMenu());
    }
}

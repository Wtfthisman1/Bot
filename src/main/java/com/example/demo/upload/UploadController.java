package com.example.demo.upload;

import com.example.demo.queue.JobQueue;
import com.example.demo.queue.ProcessingJob;
import com.example.demo.service.StorageManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;

/**
 * Принимает HTML-форму (до 5 файлов и / или 5 ссылок) и кладёт задачи в очередь.
 *
 * <p>URL формы: <b>/upload/{token}</b> — именно такой action проставляется
 * в upload.html, которую бот отдаёт пользователю.</p>
 */
@RestController
@RequestMapping("/upload")
@RequiredArgsConstructor
@Slf4j
public class UploadController {

    private static final int MAX_SLOTS = 5;

    private final UploadService  uploadService;
    private final JobQueue       jobQueue;
    private final StorageManager storageManager;


    /* ---------- отдаём форму ---------- */
    @GetMapping(value = "/{token}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<Resource> uploadForm(@PathVariable String token) {
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
        if (chatId == null)
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body("Недействительный или просроченный токен.");

        int fileCount = files != null ? files.length : 0;
        int urlCount  = urls  != null ? urls.length  : 0;

        /* ---------- 1. валидация ---------- */
        if (fileCount == 0 && urlCount == 0)
            return ResponseEntity.badRequest()
                    .body("Нужно выбрать хотя бы один файл или указать хотя бы одну ссылку.");

        if (fileCount > MAX_SLOTS || urlCount > MAX_SLOTS)
            return ResponseEntity.badRequest()
                    .body("Максимум " + MAX_SLOTS + " файлов и " + MAX_SLOTS + " ссылок за раз.");

        /* ---------- 2. файлы ---------- */
        if (files != null) {
            for (MultipartFile f : files) {
                if (f == null || f.isEmpty()) continue;
                Path dst = storageManager.uploadedPath(chatId, f.getOriginalFilename());
                f.transferTo(dst);                                     // сохраняем
                jobQueue.enqueue(ProcessingJob.newFile(chatId, dst));  // сразу в очередь
            }
        }

        /* ---------- 3. ссылки ---------- */
        if (urls != null) {
            for (String u : urls) {
                if (u == null || u.isBlank()) continue;
                jobQueue.enqueue(ProcessingJob.newLink(chatId, u.trim()));
            }
        }

        log.info("Принято от chat {}: {} файлов, {} ссылок", chatId, fileCount, urlCount);
        return ResponseEntity.ok("Принято! Задачи поставлены в очередь.");
    }
}

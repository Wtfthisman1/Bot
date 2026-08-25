package Bot.download;

import Bot.config.Profiles;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Контроллер скачивания файлов по одноразовому токену.
 *
 * <p>Ответственность: принимает токен из URL, через {@link DownloadTokenRegistry}
 * проверяет его валидность и срок действия, отдаёт файл с корректными заголовками.
 * Прямое скачивание по имени файла и поиск по всему хранилищу убраны как источник
 * IDOR. URL: <b>/download/{token}</b>.</p>
 */
@Profile(Profiles.HOME)
@RestController
@RequestMapping("/download")
@RequiredArgsConstructor
@Slf4j
public class DownloadController {

    private final DownloadTokenRegistry tokenRegistry;

    /**
     * Скачивание файла по непредсказуемому токену.
     * Любой невалидный/просроченный токен → 404 (без раскрытия деталей).
     */
    @GetMapping("/{token}")
    public ResponseEntity<Resource> downloadFile(@PathVariable String token) {
        Optional<Path> resolved = tokenRegistry.resolve(token);
        if (resolved.isEmpty()) {
            log.warn("Запрос по недействительному или просроченному токену");
            return ResponseEntity.notFound().build();
        }

        Path filePath = resolved.get();
        try {
            Resource resource = new UrlResource(filePath.toUri());
            if (!resource.exists() || !resource.isReadable()) {
                return ResponseEntity.notFound().build();
            }

            String fileName = filePath.getFileName().toString();
            String contentType = determineContentType(fileName);

            // RFC 6266: filename* понимают современные клиенты, filename — все остальные.
            // Без ASCII-фолбэка старые загрузчики сохраняли файл под именем токена.
            String encodedName = UriUtils.encode(fileName, StandardCharsets.UTF_8);
            String asciiName = fileName.replaceAll("[^A-Za-z0-9._-]", "_");

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .contentLength(resource.contentLength())
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + asciiName + "\"; filename*=UTF-8''" + encodedName)
                    .header(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, must-revalidate")
                    .header(HttpHeaders.PRAGMA, "no-cache")
                    .header(HttpHeaders.EXPIRES, "0")
                    .body(resource);

        } catch (Exception e) {
            log.error("Ошибка при отдаче файла по токену", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Определяет тип контента по расширению файла
     */
    private String determineContentType(String fileName) {
        int dot = fileName.lastIndexOf('.');
        String extension = dot >= 0 ? fileName.substring(dot + 1).toLowerCase() : "";

        return switch (extension) {
            case "mp4" -> "video/mp4";
            case "avi" -> "video/x-msvideo";
            case "mkv" -> "video/x-matroska";
            case "mov" -> "video/quicktime";
            case "webm" -> "video/webm";
            case "mp3" -> "audio/mpeg";
            case "wav" -> "audio/wav";
            case "m4a" -> "audio/mp4";
            case "flac" -> "audio/flac";
            case "ogg" -> "audio/ogg";
            default -> "application/octet-stream";
        };
    }
}

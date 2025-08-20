package com.example.demo.upload;

import com.example.demo.service.StorageManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/download")
@RequiredArgsConstructor
@Slf4j
public class DownloadController {

    private final StorageManager storageManager;
    
    // Хранилище для сопоставления коротких ID с файлами
    private final Map<String, Path> shortIdToFile = new ConcurrentHashMap<>();
    
    /**
     * Регистрирует файл с коротким ID
     */
    public void registerFile(String shortId, Path filePath) {
        shortIdToFile.put(shortId, filePath);
        log.info("Зарегистрирован файл: shortId={}, filePath={}", shortId, filePath);
    }

    /**
     * Скачивание файла по имени или короткому ID
     */
    @GetMapping("/{fileName:.+}")
    public ResponseEntity<Resource> downloadFile(@PathVariable String fileName) {
        try {
            Path filePath = null;
            
            // Сначала проверяем, является ли это коротким ID
            if (shortIdToFile.containsKey(fileName)) {
                filePath = shortIdToFile.get(fileName);
                log.info("Найден файл по короткому ID: id={}, filePath={}", fileName, filePath);
            } else {
                // Пробуем найти по имени файла (для обратной совместимости)
                try {
                    String decodedFileName = java.net.URLDecoder.decode(fileName, "UTF-8");
                    log.info("Запрос на скачивание файла: original={}, decoded={}", fileName, decodedFileName);
                    
                    filePath = storageManager.findFile(decodedFileName);
                } catch (Exception e) {
                    log.warn("Ошибка декодирования имени файла: {}", fileName, e);
                }
            }
            
            if (filePath == null || !filePath.toFile().exists()) {
                log.warn("Файл не найден: fileName={}", fileName);
                return ResponseEntity.notFound().build();
            }

            Resource resource = new UrlResource(filePath.toUri());
            
            // Определяем тип контента
            String contentType = determineContentType(fileName);
            
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .header(HttpHeaders.CONTENT_DISPOSITION, 
                            "attachment; filename=\"" + filePath.getFileName() + "\"")
                    .header(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, must-revalidate")
                    .header(HttpHeaders.PRAGMA, "no-cache")
                    .header(HttpHeaders.EXPIRES, "0")
                    .body(resource);
                    
        } catch (IOException e) {
            log.error("Ошибка при скачивании файла: {}", fileName, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Определяет тип контента по расширению файла
     */
    private String determineContentType(String fileName) {
        String extension = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();
        
        return switch (extension) {
            case "mp4" -> "video/mp4";
            case "avi" -> "video/x-msvideo";
            case "mkv" -> "video/x-matroska";
            case "mov" -> "video/quicktime";
            case "mp3" -> "audio/mpeg";
            case "wav" -> "audio/wav";
            case "m4a" -> "audio/mp4";
            case "flac" -> "audio/flac";
            case "ogg" -> "audio/ogg";
            default -> "application/octet-stream";
        };
    }
}

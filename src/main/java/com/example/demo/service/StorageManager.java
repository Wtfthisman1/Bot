package com.example.demo.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

@Component
@Slf4j
public class StorageManager {

    @Value("${app.storage.base}")
    private String storageBase;
    private Path storageRoot;

    private static final DateTimeFormatter DTF =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS");

    /* ---------------- init ---------------- */

    @PostConstruct
    void init() throws IOException {
        storageRoot = Path.of(storageBase).toAbsolutePath();
        Files.createDirectories(storageRoot);
    }

    /* ---------------- public API ---------------- */

    public Path userRoot(long chatId) throws IOException {
        Path userPath = storageRoot.resolve(String.valueOf(chatId));
        Files.createDirectories(userPath);
        return userPath;
    }

    public Path uploadedPath(long chatId, String originalName) throws IOException {
        return ensureSubDir(chatId, "uploaded")
                .resolve(fileName(originalName, null));
    }

    public Path downloadedPath(long chatId, String url) throws IOException {
        String title = videoTitle(url);
        return ensureSubDir(chatId, "downloaded")
                .resolve(fileName(title, ".mp4"));


    }

    public Path transcriptPath(long chatId, String baseName) throws IOException {
        String name = sanitize(baseName).replaceFirst("\\.[^.]+$", "");
        return ensureSubDir(chatId, "transcripts")
                .resolve(name+ ".txt");
    }

    public Stream<Path> listFiles(long chatId) throws IOException {
        return Stream.concat(
                        Files.list(ensureSubDir(chatId, "uploaded")),
                        Files.list(ensureSubDir(chatId, "downloaded")))
                .filter(this::isVideo);
    }

    public Stream<Path> listTranscripts(long chatId) throws IOException {
        return Files.list(ensureSubDir(chatId, "transcripts"))
                .filter(p -> p.toString().endsWith(".txt"));
    }

    public Path getStorageRoot() {
        return storageRoot;
    }

    public Path getTranscriptsDir(long chatId) throws IOException {
        return ensureSubDir(chatId, "transcripts");
    }

    /* ---------------- helpers ---------------- */



    private String videoTitle(String url) throws IOException {
        ProcessBuilder pb = new ProcessBuilder("yt-dlp", 
                "-e",
                "--no-warnings", 
                "--ignore-errors", 
                "--no-playlist",
                "--quiet",
                url)
                .redirectError(ProcessBuilder.Redirect.DISCARD);  // Игнорируем stderr полностью
        
        Process p = pb.start();
        
        try {
            // Ждем завершения процесса с таймаутом
            boolean completed = p.waitFor(15, TimeUnit.SECONDS);
            if (!completed) {
                log.warn("Таймаут получения названия видео для URL: {}", url);
                p.destroyForcibly();
                return "video_" + System.currentTimeMillis();
            }
            
            // Проверяем код выхода
            int exitCode = p.exitValue();
            if (exitCode != 0) {
                log.warn("yt-dlp завершился с ошибкой {} для URL: {}", exitCode, url);
                return "video_" + System.currentTimeMillis();
            }
            
            // Читаем только stdout и фильтруем результат
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    // Фильтруем строки - убираем предупреждения и ошибки
                    if (!isWarningOrError(line)) {
                        output.append(line).append('\n');
                    }
                }
            }
            
            // Обрабатываем результат
            String result = output.toString().trim();
            if (result.isEmpty()) {
                log.warn("Пустое название видео для URL: {}", url);
                return "video_" + System.currentTimeMillis();
            }
            
            // Берем первую непустую строку (название)
            String[] lines = result.split("\n");
            String title = null;
            for (String line : lines) {
                line = line.trim();
                if (!line.isEmpty() && !isWarningOrError(line)) {
                    title = line;
                    break;
                }
            }
            
            if (title == null || title.isEmpty()) {
                log.warn("Не найдено название видео для URL: {}", url);
                return "video_" + System.currentTimeMillis();
            }
            
            // Ограничиваем длину названия
            if (title.length() > 100) {
                title = title.substring(0, 97) + "...";
            }
            
            log.debug("Получено название видео: '{}' для URL: {}", title, url);
            return title;
            
        } catch (InterruptedException e) {
            log.warn("Прерывание при получении названия видео для URL: {}", url, e);
            Thread.currentThread().interrupt();
            return "video_" + System.currentTimeMillis();
        } catch (Exception e) {
            log.warn("Ошибка получения названия видео для URL: {}", url, e);
            return "video_" + System.currentTimeMillis();
        } finally {
            // Убеждаемся, что процесс завершен
            if (p.isAlive()) {
                p.destroyForcibly();
            }
        }
    }
    
    /**
     * Проверяет, является ли строка предупреждением или ошибкой
     */
    private boolean isWarningOrError(String line) {
        if (line == null || line.trim().isEmpty()) {
            return true;
        }
        
        String lowerLine = line.toLowerCase();
        
        // Фильтруем предупреждения и ошибки
        return lowerLine.contains("warning") ||
               lowerLine.contains("error") ||
               lowerLine.contains("failed") ||
               lowerLine.contains("unable") ||
               lowerLine.contains("invalid") ||
               lowerLine.contains("unsupported") ||
               lowerLine.contains("timeout") ||
               lowerLine.contains("network") ||
               lowerLine.contains("connection") ||
               lowerLine.startsWith("[") && lowerLine.contains("]") ||
               lowerLine.startsWith("error:") ||
               lowerLine.startsWith("warning:");
    }



    private String fileName(String base, String ext) {
        String ts = DTF.format(LocalDateTime.now());
        String safe = sanitize(base);
        return safe + "_" + ts  + (ext != null ? ext : "");
    }

    private String sanitize(String name) {
        String s = name.replaceAll("[^\\p{L}\\p{N}._-]", "_");
        s = s.replaceAll("_+", "_");
        return s.replaceAll("^_+|_+$", "");
    }


    private Path ensureSubDir(long chatId, String dirName) throws IOException {
        Path dir = userRoot(chatId).resolve(dirName);
        Files.createDirectories(dir);
        return dir;
    }

    private boolean isVideo(Path p) {
        String n = p.getFileName().toString().toLowerCase();
        return n.endsWith(".mp4") || n.endsWith(".webm") ||
                n.endsWith(".mkv") || n.endsWith(".mov") ||
                n.endsWith(".avi");
    }
}

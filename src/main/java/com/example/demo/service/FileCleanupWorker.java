package com.example.demo.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Component
@RequiredArgsConstructor
@Slf4j
public class FileCleanupWorker {

    @Value("${cleanup.retention-days:14}")
    private int retentionDays;

    @Value("${cleanup.enabled:true}")
    private boolean cleanupEnabled;

    private final StorageManager storageManager;

    /**
     * Запускается каждый день в 2:00 утра
     */
    @Scheduled(cron = "${cleanup.schedule:0 0 2 * * ?}")
    public void cleanupOldFiles() {
        if (!cleanupEnabled) {
            log.info("Очистка файлов отключена");
            return;
        }

        log.info("Начинаю очистку файлов старше {} дней", retentionDays);

        try {
            // Считаем дату отсечки: всё, что старше retentionDays, удаляем
            Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
            Path storageRoot = storageManager.getStorageRoot();

            // Очищаем только видео/аудио файлы (по расширениям), смотрим дату создания файла
            Files.list(storageRoot)
                .filter(Files::isDirectory)
                .forEach(userDir -> cleanupUserFiles(userDir, cutoff));

            log.info("Очистка завершена");

        } catch (Exception e) {
            log.error("Ошибка при очистке файлов", e);
        }
    }


    /**
     * Очищает файлы конкретного пользователя
     */
    private void cleanupUserFiles(Path userDir, Instant cutoff) {
        try {
            // Проверяем, что это папка пользователя (числовой ID)
            long chatId = Long.parseLong(userDir.getFileName().toString());
            log.debug("Очистка файлов пользователя: {}", chatId);

            // Очищаем видео и аудио файлы
            cleanupDirectory(userDir.resolve("uploaded"), cutoff);
            cleanupDirectory(userDir.resolve("downloaded"), cutoff);

            // Транскрипции НЕ удаляем!

        } catch (NumberFormatException e) {
            log.warn("Неверное имя папки пользователя: {}", userDir.getFileName());
        } catch (Exception e) {
            log.error("Ошибка очистки файлов пользователя {}", userDir.getFileName(), e);
        }
    }

    /**
     * Очищает файлы в указанной директории
     */
    private void cleanupDirectory(Path dir, Instant cutoff) {
        if (!Files.exists(dir)) {
            log.debug("Директория не существует: {}", dir);
            return;
        }

        try {
            long deletedCount = Files.list(dir)
                .filter(Files::isRegularFile)
                .filter(file -> isOldFile(file, cutoff))
                .mapToLong(this::deleteFile)
                .sum();

            if (deletedCount > 0) {
                log.info("Удалено {} файлов из {}", deletedCount, dir);
            }

        } catch (IOException e) {
            log.error("Ошибка очистки директории {}", dir, e);
        }
    }

    /**
     * Проверяет, является ли файл старым
     */
    private boolean isOldFile(Path file, Instant cutoff) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
            return attrs.creationTime().toInstant().isBefore(cutoff);
        } catch (IOException e) {
            log.warn("Не удалось получить атрибуты файла: {}", file, e);
            return false;
        }
    }

    /**
     * Удаляет файл и возвращает его размер
     */
    private long deleteFile(Path file) {
        try {
            long size = Files.size(file);
            Files.delete(file);
            log.debug("Удален файл: {} ({} bytes)", file, size);
            return size;
        } catch (IOException e) {
            log.error("Ошибка удаления файла {}", file, e);
            return 0;
        }
    }

    /**
     * Ручная очистка для конкретного пользователя
     */
    public void cleanupUserFiles(long chatId) {
        try {
            Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
            Path userDir = storageManager.userRoot(chatId);
            cleanupUserFiles(userDir, cutoff);
        } catch (Exception e) {
            log.error("Ошибка ручной очистки файлов пользователя {}", chatId, e);
        }
    }

    /**
     * Получает статистику по файлам пользователя
     */
    public FileStats getUserFileStats(long chatId) {
        try {
            Path userDir = storageManager.userRoot(chatId);
            Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);

            long uploadedFiles = countFilesInDir(userDir.resolve("uploaded"));
            long downloadedFiles = countFilesInDir(userDir.resolve("downloaded"));
            long transcriptFiles = countFilesInDir(userDir.resolve("transcripts"));
            long oldFiles = countOldFiles(userDir, cutoff);

            return new FileStats(uploadedFiles, downloadedFiles, transcriptFiles, oldFiles);

        } catch (Exception e) {
            log.error("Ошибка получения статистики файлов пользователя {}", chatId, e);
            return new FileStats(0, 0, 0, 0);
        }
    }

    private long countFilesInDir(Path dir) {
        if (!Files.exists(dir)) return 0;
        try {
            return Files.list(dir).filter(Files::isRegularFile).count();
        } catch (IOException e) {
            return 0;
        }
    }

    private long countOldFiles(Path userDir, Instant cutoff) {
        try {
            long uploadedOld = Files.list(userDir.resolve("uploaded"))
                .filter(Files::isRegularFile)
                .filter(file -> isOldFile(file, cutoff))
                .count();

            long downloadedOld = Files.list(userDir.resolve("downloaded"))
                .filter(Files::isRegularFile)
                .filter(file -> isOldFile(file, cutoff))
                .count();

            return uploadedOld + downloadedOld;
        } catch (IOException e) {
            return 0;
        }
    }

    /**
     * Статистика файлов пользователя
     */
    public record FileStats(
        long uploadedFiles,
        long downloadedFiles,
        long transcriptFiles,
        long oldFiles
    ) {}
}


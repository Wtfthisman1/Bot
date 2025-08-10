package com.example.demo.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class TranscriptService {

    private final StorageManager storageManager;

    /**
     * Получает список всех транскрипций пользователя
     */
    public List<TranscriptInfo> getUserTranscripts(long chatId) {
        try {
            return Files.list(storageManager.getTranscriptsDir(chatId))
                .filter(p -> p.toString().endsWith(".txt"))
                .map(this::parseTranscriptInfo)
                .sorted((a, b) -> b.createdAt().compareTo(a.createdAt())) // Сначала новые
                .collect(Collectors.toList());
        } catch (IOException e) {
            log.error("Ошибка получения транскрипций для пользователя {}", chatId, e);
            return List.of();
        }
    }

    /**
     * Получает конкретную транскрипцию по имени файла
     */
    public Path getTranscriptFile(long chatId, String fileName) throws IOException {
        Path transcriptDir = storageManager.getTranscriptsDir(chatId);
        Path file = transcriptDir.resolve(fileName);
        
        if (!Files.exists(file)) {
            throw new IOException("Транскрипция не найдена: " + fileName);
        }
        
        return file;
    }

    /**
     * Получает статистику транскрипций пользователя
     */
    public TranscriptStats getUserStats(long chatId) {
        try {
            List<TranscriptInfo> transcripts = getUserTranscripts(chatId);
            
            long totalFiles = transcripts.size();
            long totalSize = transcripts.stream()
                .mapToLong(TranscriptInfo::fileSize)
                .sum();
            
            return new TranscriptStats(totalFiles, totalSize);
            
        } catch (Exception e) {
            log.error("Ошибка получения статистики транскрипций для пользователя {}", chatId, e);
            return new TranscriptStats(0, 0);
        }
    }

    /**
     * Удаляет транскрипцию пользователя
     */
    public boolean deleteTranscript(long chatId, String fileName) {
        try {
            Path file = getTranscriptFile(chatId, fileName);
            Files.delete(file);
            log.info("Удалена транскрипция: {} пользователя {}", fileName, chatId);
            return true;
        } catch (IOException e) {
            log.error("Ошибка удаления транскрипции: {} пользователя {}", fileName, chatId, e);
            return false;
        }
    }

    /**
     * Парсит информацию о транскрипции из файла
     */
    private TranscriptInfo parseTranscriptInfo(Path file) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
            String content = Files.readString(file);
            
            return new TranscriptInfo(
                file.getFileName().toString(),
                content.length(),
                attrs.creationTime().toInstant(),
                file
            );
        } catch (IOException e) {
            log.warn("Ошибка чтения файла транскрипции: {}", file, e);
            return new TranscriptInfo(
                file.getFileName().toString(),
                0,
                Instant.now(),
                file
            );
        }
    }

    /**
     * Форматирует размер файла для отображения
     */
    public String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    /**
     * Форматирует дату для отображения
     */
    public String formatDate(Instant date) {
        return date.toString().substring(0, 16).replace("T", " ");
    }

    /**
     * Информация о транскрипции
     */
    public record TranscriptInfo(
        String fileName,
        long fileSize,
        Instant createdAt,
        Path filePath
    ) {}

    /**
     * Статистика транскрипций
     */
    public record TranscriptStats(
        long totalFiles,
        long totalSize
    ) {}
}


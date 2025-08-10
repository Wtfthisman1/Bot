package com.example.demo.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.GetFile;
import org.telegram.telegrambots.meta.api.objects.File;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class TelegramFileDownloader {

    private final StorageManager storageManager;
    private final ApplicationContext applicationContext; // Используем ApplicationContext
    
    @Value("${bot.key}")
    private String botToken;

    /**
     * Получает TelegramBot из контекста
     */
    private TelegramBot getTelegramBot() {
        return applicationContext.getBean(TelegramBot.class);
    }

    /**
     * Скачивает файл из Telegram по fileId
     */
    public Path downloadFile(String fileId, long chatId, String originalName) throws Exception {
        // Получаем информацию о файле
        GetFile getFile = new GetFile();
        getFile.setFileId(fileId);
        
        File file = execute(getFile);
        if (file == null) {
            throw new IOException("Не удалось получить информацию о файле: " + fileId);
        }

        // Формируем URL для скачивания
        String fileUrl = "https://api.telegram.org/file/bot" + botToken + "/" + file.getFilePath();
        
        // Определяем расширение файла
        String extension = getFileExtension(file.getFilePath());
        if (extension == null) {
            extension = getExtensionFromName(originalName);
        }
        
        // Создаем временное имя файла
        String tempFileName = "telegram_" + UUID.randomUUID().toString().substring(0, 8) + extension;
        
        // Путь для сохранения
        Path downloadPath = storageManager.uploadedPath(chatId, tempFileName);
        Files.createDirectories(downloadPath.getParent());
        
        // Скачиваем файл
        try (InputStream in = new URL(fileUrl).openStream()) {
            Files.copy(in, downloadPath, StandardCopyOption.REPLACE_EXISTING);
        }
        
        log.info("Скачан файл из Telegram: {} -> {}", fileId, downloadPath);
        return downloadPath;
    }

    /**
     * Скачивает голосовое сообщение
     */
    public Path downloadVoice(String fileId, long chatId) throws Exception {
        return downloadFile(fileId, chatId, "voice.ogg");
    }

    /**
     * Скачивает аудио файл
     */
    public Path downloadAudio(String fileId, long chatId, String originalName) throws Exception {
        return downloadFile(fileId, chatId, originalName);
    }

    /**
     * Скачивает видео файл
     */
    public Path downloadVideo(String fileId, long chatId, String originalName) throws Exception {
        return downloadFile(fileId, chatId, originalName);
    }

    /**
     * Скачивает документ
     */
    public Path downloadDocument(String fileId, long chatId, String originalName) throws Exception {
        return downloadFile(fileId, chatId, originalName);
    }

    /**
     * Получает расширение файла из пути
     */
    private String getFileExtension(String filePath) {
        if (filePath == null) return null;
        int lastDot = filePath.lastIndexOf('.');
        return lastDot > 0 ? filePath.substring(lastDot) : null;
    }

    /**
     * Получает расширение из имени файла
     */
    private String getExtensionFromName(String fileName) {
        if (fileName == null) return ".bin";
        int lastDot = fileName.lastIndexOf('.');
        return lastDot > 0 ? fileName.substring(lastDot) : ".bin";
    }

    /**
     * Выполняет запрос к Telegram API
     */
    private File execute(GetFile getFile) throws TelegramApiException {
        return getTelegramBot().execute(getFile);
    }
}


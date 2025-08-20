package com.example.demo.service;

import com.example.demo.queue.JobQueue;
import com.example.demo.queue.ProcessingJob;
import com.example.demo.upload.DownloadController;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class DownloadService {
    
    @Value("${download.base-url:http://localhost:8080}")
    private String downloadBaseUrl;
    
    @jakarta.annotation.PostConstruct
    void init() {
        // Попробуем загрузить переменную напрямую из окружения
        String envDownloadUrl = System.getenv("DOWNLOAD_BASE_URL");
        if (envDownloadUrl != null && !envDownloadUrl.isEmpty()) {
            downloadBaseUrl = envDownloadUrl;
            log.info("DownloadService инициализирован с downloadBaseUrl из env: {}", downloadBaseUrl);
        } else {
            log.info("DownloadService инициализирован с downloadBaseUrl из properties: {}", downloadBaseUrl);
        }
        log.info("DOWNLOAD_BASE_URL из env: {}", System.getenv("DOWNLOAD_BASE_URL"));
    }
    
    private final StorageManager storageManager;
    private final JobQueue jobQueue;
    private final MessageSender messageSender;
    private final DownloadController downloadController;
    
    // Хранилище для отслеживания загрузок пользователей
    private final Map<String, DownloadInfo> downloads = new HashMap<>();
    
    /**
     * Создает задачу на загрузку файла
     */
    public void createDownloadTask(long chatId, String url, String name) {
        try {
            // Создаем уникальный ID для загрузки
            String downloadId = UUID.randomUUID().toString();
            
            log.info("Создаю задачу загрузки: chatId={}, url={}, name={}, downloadId={}", 
                    chatId, url, name, downloadId);

            // Добавляем в очередь на загрузку
            ProcessingJob job = ProcessingJob.newDownload(chatId, url, downloadId);
            jobQueue.enqueue(job);
            
            log.info("Задача добавлена в очередь: jobId={}, downloadId={}", job.id(), downloadId);

            // Сохраняем информацию о загрузке
            downloads.put(downloadId, new DownloadInfo(chatId, url, name, System.currentTimeMillis()));
            
            log.info("Информация о загрузке сохранена: downloadId={}, downloads.size={}", 
                    downloadId, downloads.size());

            // Не отправляем ссылку на скачивание, так как файл еще не загружен
            messageSender.sendMessage(chatId,
                "📥 Загрузка начата!\n\n" +
                "🔗 Ссылка: " + url + "\n" +
                "👤 Пользователь: " + name + "\n\n" +
                "⏳ Файл загружается. Ссылка для скачивания появится после завершения загрузки.");
        } catch (Exception e) {
            log.error("Ошибка создания задачи загрузки", e);
            messageSender.sendMessage(chatId, "❌ Ошибка создания задачи загрузки: " + e.getMessage());
        }
    }
    
    /**
     * Обрабатывает завершенную загрузку
     */
    public void handleDownloadComplete(String downloadId, Path filePath) {
        DownloadInfo info = downloads.get(downloadId);
        if (info == null) {
            log.warn("Не найдена информация о загрузке: {}", downloadId);
            return;
        }
        
        try {
            // Генерируем ссылку для скачивания
            String downloadLink = generateDownloadLink(filePath, info);
            
            log.info("Обрабатываю завершенную загрузку: downloadId={}, fileName={}, downloadLink={}", 
                    downloadId, filePath.getFileName(), downloadLink);
            
            String message = """
                ✅ Загрузка завершена!
                
                📁 Файл: %s
                📏 Размер: %s
                👤 Пользователь: %s
                
                🔗 Скачать файл: %s
                
                ⏰ Ссылка действительна 24 часа
                """.formatted(
                    filePath.getFileName(),
                    formatFileSize(Files.size(filePath)),
                    info.userName(),
                    downloadLink
                );
            
            log.info("Отправляю сообщение пользователю {}: {}", info.chatId(), message);
            messageSender.sendMessage(info.chatId(), message);
            
            // Удаляем информацию о загрузке
            downloads.remove(downloadId);
            
        } catch (Exception e) {
            log.error("Ошибка обработки завершенной загрузки", e);
            messageSender.sendMessage(info.chatId(), 
                "❌ Ошибка обработки загрузки: " + e.getMessage());
        }
    }
    
    /**
     * Обрабатывает ошибку загрузки
     */
    public void handleDownloadError(String downloadId, String error) {
        DownloadInfo info = downloads.get(downloadId);
        if (info == null) {
            log.warn("Не найдена информация о загрузке: {}", downloadId);
            return;
        }
        
        messageSender.sendMessage(info.chatId(), 
            "❌ Ошибка загрузки:\n\n" + error);
        
        // Удаляем информацию о загрузке
        downloads.remove(downloadId);
    }
    
    /**
     * Генерирует ссылку для скачивания файла
     */
    private String generateDownloadLink(Path filePath, DownloadInfo info) {
        try {
            // Используем короткий ID вместо полного имени файла
            String shortId = generateShortId(filePath);
            
            // Регистрируем файл в DownloadController
            downloadController.registerFile(shortId, filePath);
            
            String link = downloadBaseUrl + "/download/" + shortId;
            log.info("Генерирую ссылку для скачивания: baseUrl={}, fileName={}, shortId={}, link={}", 
                    downloadBaseUrl, filePath.getFileName(), shortId, link);
            return link;
        } catch (Exception e) {
            log.error("Ошибка генерации ссылки для файла: {}", filePath.getFileName(), e);
            // Fallback: используем оригинальное имя файла
            String link = downloadBaseUrl + "/download/" + filePath.getFileName();
            return link;
        }
    }
    
    /**
     * Генерирует короткий ID для файла
     */
    private String generateShortId(Path filePath) {
        // Используем хеш от имени файла и времени создания
        String hash = String.valueOf(filePath.getFileName().toString().hashCode());
        return hash.replace("-", "n"); // Заменяем минус на 'n' для избежания проблем с URL
    }
    
    /**
     * Форматирует размер файла
     */
    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }
    
    /**
     * Получает активные загрузки пользователя
     */
    public List<DownloadInfo> getActiveDownloads(long chatId) {
        return downloads.values().stream()
                .filter(info -> info.chatId() == chatId)
                .collect(java.util.stream.Collectors.toList());
    }
    
    /**
     * Информация о загрузке
     */
    public record DownloadInfo(long chatId, String url, String userName, long startTime) {}
}

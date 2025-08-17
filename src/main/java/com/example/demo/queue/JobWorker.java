package com.example.demo.queue;


import com.example.demo.service.DownloaderExecutor;
import com.example.demo.service.MessageSender;
import com.example.demo.service.TranscribeExecutor;
import com.example.demo.service.DownloadService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Component
@RequiredArgsConstructor
@Slf4j
public class JobWorker implements Runnable {

    private final JobQueue queue;
    private final DownloaderExecutor downloader;
    private final TranscribeExecutor transcriber;
    private final MessageSender messageSender;
    private final DownloadService downloadService;

    private volatile boolean running = true;
    private Thread worker;

    @PostConstruct
    void start() {
        worker = new Thread(this, "job-worker");
        worker.start();
    }

    @PreDestroy
    void stop() {
        running = false;
        worker.interrupt();
    }

    @Override
    public void run() {
        while (running) {
            try {
                ProcessingJob job = queue.take();   // блокируемся
                switch (job.state()) {
                    case NEW        -> download(job);
                    case DOWNLOADED -> transcribe(job);
                }
            } catch (InterruptedException ie) {
                if (!running) break;   // нормальный shutdown
                Thread.currentThread().interrupt();
            } catch (Exception ex) {
                log.error("Ошибка обработки задачи", ex);
            }
        }
        log.info("Job-worker завершён");
    }

    private void download(ProcessingJob job) {
        try {
            Path file = downloader.download(job.chatId(), job.url());
            log.info("Скачано {}", file);
            
            // Проверяем, является ли это задачей загрузки
            if (job.downloadId() != null) {
                downloadService.handleDownloadComplete(job.downloadId(), file);
            } else {
                // Обычная задача транскрибирования
                queue.enqueue(job.withFile(file));
            }
        } catch (Exception e) {
            log.error("Ошибка скачивания для URL: {}", job.url(), e);
            
            // Проверяем, является ли это задачей загрузки
            if (job.downloadId() != null) {
                downloadService.handleDownloadError(job.downloadId(), getErrorMessage(job.url(), e));
            } else {
                // Обычная задача транскрибирования
                String errorMessage = getErrorMessage(job.url(), e);
                messageSender.sendMessage(job.chatId(), errorMessage);
            }
        }
    }

    private void transcribe(ProcessingJob job) {
        try {
            Path txt = transcriber.run(job.chatId(), job.filePath());
            log.info("Транскрипция готова {}", txt);
            messageSender.sendTranscript(job.chatId(), txt);
        } catch (Exception e) {
            log.error("Ошибка транскрипции для файла: {}", job.filePath(), e);
            messageSender.sendMessage(job.chatId(), 
                "❌ Ошибка транскрипции файла. Возможно, файл поврежден или имеет неподдерживаемый формат.");
        }
    }

    /**
     * Формирует понятное сообщение об ошибке для пользователя
     */
    private String getErrorMessage(String url, Exception e) {
        String errorText = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        
        if (errorText.contains("unsupported url")) {
            return String.format("""
                ❌ Неподдерживаемая ссылка: %s
                
                🔗 Поддерживаемые платформы:
                • YouTube (youtube.com, youtu.be)
                • Vimeo (vimeo.com)
                • TikTok (tiktok.com)
                • Instagram (instagram.com)
                • Twitter/X (twitter.com, x.com)
                • Facebook (facebook.com)
                
                💡 Отправьте ссылку на видео с одной из этих платформ.
                """, url);
        }
        
        if (errorText.contains("video unavailable") || errorText.contains("private")) {
            return String.format("""
                🔒 Видео недоступно: %s
                
                Возможные причины:
                • Видео приватное или удалено
                • Требуется авторизация
                • Географические ограничения
                • Возрастные ограничения
                """, url);
        }
        
        if (errorText.contains("network") || errorText.contains("connection")) {
            return String.format("""
                🌐 Ошибка сети: %s
                
                Проблемы с подключением к серверу.
                Попробуйте позже или проверьте ссылку.
                """, url);
        }
        
        // Общая ошибка
        return String.format("""
            ❌ Ошибка обработки ссылки: %s
            
            Причина: %s
            
            💡 Попробуйте:
            • Проверить правильность ссылки
            • Убедиться, что видео доступно
            • Использовать другую ссылку
            """, url, e.getMessage());
    }
}

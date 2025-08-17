package com.example.demo.service;

import com.example.demo.queue.JobQueue;
import com.example.demo.queue.ProcessingJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class StatusService {
    
    private final JobQueue jobQueue;
    private final DownloadService downloadService;
    
    // Кэш для хранения статусов задач (в реальном проекте лучше использовать БД)
    private final Map<String, TaskStatus> taskStatuses = new ConcurrentHashMap<>();
    
    /**
     * Получает статус задач пользователя
     */
    public UserStatus getUserStatus(long chatId) {
        try {
            // Получаем задачи из очереди
            List<ProcessingJob> userJobs = jobQueue.getUserJobs(chatId);
            
            // Подсчитываем статистику
            long pendingCount = userJobs.stream()
                    .filter(job -> job.state() == ProcessingJob.State.NEW)
                    .count();
            
            long processingCount = userJobs.stream()
                    .filter(job -> job.state() == ProcessingJob.State.DOWNLOADED)
                    .count();
            
            long totalCount = userJobs.size();
            
            // Получаем активные загрузки
            List<DownloadInfo> activeDownloads = getActiveDownloads(chatId);
            
            return new UserStatus(
                chatId,
                pendingCount,
                processingCount,
                totalCount,
                activeDownloads.size(),
                activeDownloads
            );
            
        } catch (Exception e) {
            log.error("Ошибка получения статуса для пользователя: {}", chatId, e);
            return new UserStatus(chatId, 0, 0, 0, 0, List.of());
        }
    }
    
    /**
     * Обновляет статус задачи
     */
    public void updateTaskStatus(String taskId, TaskStatus status) {
        taskStatuses.put(taskId, status);
    }
    
    /**
     * Получает активные загрузки пользователя
     */
    private List<DownloadInfo> getActiveDownloads(long chatId) {
        return downloadService.getActiveDownloads(chatId).stream()
                .map(info -> new DownloadInfo(
                    info.url(), // Используем URL как downloadId для совместимости
                    info.url(),
                    "Загрузка",
                    info.startTime()
                ))
                .collect(java.util.stream.Collectors.toList());
    }
    
    /**
     * Статус пользователя
     */
    public record UserStatus(
        long chatId,
        long pendingTasks,
        long processingTasks,
        long totalTasks,
        long activeDownloads,
        List<DownloadInfo> downloads
    ) {}
    
    /**
     * Статус задачи
     */
    public record TaskStatus(
        String taskId,
        String status,
        String progress,
        long startTime
    ) {}
    
    /**
     * Информация о загрузке
     */
    public record DownloadInfo(
        String downloadId,
        String url,
        String status,
        long startTime
    ) {}
}

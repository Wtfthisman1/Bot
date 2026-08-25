package Bot.service;

/**
 * Сервис формирования статуса задач пользователя.
 *
 * <p>Ответственность: агрегирует сведения из очереди и активных задач,
 * предоставляет срез по количеству и деталям загрузок. Связан с
 * {@link Bot.processing.JobQueue} и {@link DownloadService}. Основные
 * методы: {@code getUserStatus}, {@code markJobActive}, {@code markJobCompleted}.</p>
 */
import Bot.download.DownloadService;
import Bot.processing.JobQueue;
import Bot.processing.ProcessingJob;
import Bot.download.DownloadService.DownloadInfo;
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

    // Отслеживание активных задач
    private final Map<String, ProcessingJob> activeJobs = new ConcurrentHashMap<>();

    /**
     * Получает статус задач пользователя
     */
    public UserStatus getUserStatus(long chatId) {
        try {
            // Получаем задачи из очереди
            List<ProcessingJob> userJobs = jobQueue.getUserJobs(chatId);

            // Получаем активные задачи
            List<ProcessingJob> activeUserJobs = activeJobs.values().stream()
                    .filter(job -> job.chatId() == chatId)
                    .collect(Collectors.toList());

            // Объединяем задачи из очереди и активные
            List<ProcessingJob> allJobs = new java.util.ArrayList<>();
            allJobs.addAll(userJobs);
            allJobs.addAll(activeUserJobs);

            // Подсчитываем статистику
            long pendingCount = allJobs.stream()
                    .filter(job -> job.state() == ProcessingJob.State.NEW)
                    .count();

            long processingCount = allJobs.stream()
                    .filter(job -> job.state() == ProcessingJob.State.DOWNLOADED)
                    .count();

            long totalCount = allJobs.size();

            // Получаем активные загрузки
            List<DownloadInfo> activeDownloads = downloadService.getActiveDownloads(chatId);

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
     * Отмечает задачу как активную
     */
    public void markJobActive(ProcessingJob job) {
        activeJobs.put(job.id(), job);
    }

    /**
     * Отмечает задачу как завершенную
     */
    public void markJobCompleted(String jobId) {
        activeJobs.remove(jobId);
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
}

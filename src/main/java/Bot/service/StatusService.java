package Bot.service;

/**
 * Сводка «что у меня сейчас в работе» для кнопки «Статус».
 *
 * <p>Ответственность: сложить незавершённые задачи пользователя и его активные
 * загрузки в один ответ. Связан с {@link JobStore} и {@link DownloadService}.
 * Основной метод: {@code getUserStatus}.</p>
 *
 * <p>Раньше сервис вёл свою карту активных задач и спрашивал очередь в памяти —
 * после перезапуска бот отвечал «задач нет», хотя файлы качались. Теперь
 * состояние задач живёт в базе, и второй источник правды не нужен.</p>
 */
import Bot.download.DownloadService;
import Bot.download.DownloadService.DownloadInfo;
import Bot.owner.Owner;
import Bot.processing.JobStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class StatusService {

    private final JobStore jobStore;
    private final DownloadService downloadService;

    public UserStatus getUserStatus(long chatId) {
        try {
            JobStore.OwnerLoad load = jobStore.load(Owner.telegram(chatId));
            List<DownloadInfo> activeDownloads = downloadService.getActiveDownloads(chatId);

            return new UserStatus(
                    chatId,
                    load.queued(),
                    load.running(),
                    load.total(),
                    activeDownloads.size(),
                    activeDownloads);

        } catch (Exception e) {
            // Статус — вспомогательная команда: молча отдать нули лучше,
            // чем уронить обработку сообщения
            log.error("Ошибка получения статуса для пользователя: {}", chatId, e);
            return new UserStatus(chatId, 0, 0, 0, 0, List.of());
        }
    }

    /**
     * Статус пользователя.
     *
     * @param pendingTasks    ждут свободного воркера
     * @param processingTasks считаются прямо сейчас
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

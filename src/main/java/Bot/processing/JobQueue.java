package Bot.processing;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.Collectors;

@Component
@Slf4j
public class JobQueue {


    /**
     * Потокобезопасная очередь задач обработки.
     *
     * <p>Ответственность: приём новых задач, выдача потребителю и базовые
     * выборки по пользователю. Используется {@link JobWorker} и сервисами,
     * которые ставят задачи. Основные методы: {@code enqueue}, {@code take},
     * {@code getUserJobs}.</p>
     */

    private final BlockingQueue<ProcessingJob> q = new LinkedBlockingQueue<>();

    public void enqueue(ProcessingJob job) {
        if (q.offer(job)) {
            log.info("Задача поставлена в очередь: jobId={}, chatId={}, состояние={}, в очереди={}",
                    job.id(), job.chatId(), job.state(), q.size());
        } else {
            // LinkedBlockingQueue без границы сюда не попадает, но молчать нельзя
            log.error("Не удалось поставить задачу в очередь: jobId={}, chatId={}",
                    job.id(), job.chatId());
        }
    }

    public ProcessingJob take() throws InterruptedException {
        ProcessingJob job = q.take();
        log.debug("Задача взята из очереди: jobId={}, chatId={}, осталось={}",
                job.id(), job.chatId(), q.size());
        return job;
    }

    public int size()                                     { return q.size(); }

    /**
     * Получает все задачи пользователя, ожидающие в очереди
     */
    public List<ProcessingJob> getUserJobs(long chatId) {
        return q.stream()
                .filter(job -> job.chatId() == chatId)
                .collect(Collectors.toList());
    }
}

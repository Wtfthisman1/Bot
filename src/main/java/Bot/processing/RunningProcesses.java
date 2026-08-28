package Bot.processing;

/**
 * Внешние процессы, запущенные задачами прямо сейчас.
 *
 * <p>Ответственность: одно место, где известно, каким процессом занята каждая
 * задача. Нужно ради отмены: пометить задачу в базе мало — Whisper продолжит
 * считать и будет держать видеокарту, пока не досчитает запись до конца.</p>
 *
 * <p>Реестр в памяти, а не в базе: он описывает не задачу, а живой процесс
 * этой машины. После перезапуска ни одного из них не останется, а задачи,
 * оставшиеся в работе, и так возвращаются в очередь на старте воркера.</p>
 */
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Slf4j
public class RunningProcesses {

    private final Map<UUID, Process> processes = new ConcurrentHashMap<>();

    /**
     * Запоминает процесс задачи на время его работы.
     *
     * @return сам процесс — чтобы вызов вставлялся в цепочку без лишней строки
     */
    public Process watch(UUID jobId, Process process) {
        if (jobId != null) {
            processes.put(jobId, process);
        }
        return process;
    }

    /** Процесс завершился сам: держать его в реестре больше незачем. */
    public void forget(UUID jobId) {
        if (jobId != null) {
            processes.remove(jobId);
        }
    }

    /**
     * Убивает процесс задачи, если он ещё жив.
     *
     * <p>Мягко не просим: Whisper не слушает сигналов между сегментами, а
     * человек, нажавший «отменить», ждёт, что видеокарта освободится сейчас,
     * а не через полчаса.</p>
     *
     * @return был ли кого убивать
     */
    public boolean kill(UUID jobId) {
        Process process = processes.remove(jobId);
        if (process == null) {
            return false;
        }
        log.info("Останавливаю процесс отменённой задачи: jobId={}, pid={}",
                jobId, process.pid());
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        return true;
    }
}

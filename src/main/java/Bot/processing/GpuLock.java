package Bot.processing;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;

/**
 * Пропуск на видеокарту: одновременно её занимает только одна задача.
 *
 * <p>Воркеров в пуле несколько, и до этого класса они могли запустить Whisper
 * параллельно. Модель {@code large-v3} во {@code float16} занимает около
 * 4,7 ГБ, поэтому две сразу в 8 ГБ видеопамяти не помещаются — вторая падала
 * бы с CUDA out of memory. Пока пользователь один, это незаметно; с публичным
 * сайтом ломалось бы на втором посетителе.</p>
 *
 * <p>Ограничение намеренно стоит здесь, а не в размере пула воркеров:
 * скачивание с площадки видеокарту не трогает и должно идти параллельно. Сужать
 * пул до одного потока значило бы заодно запретить и параллельные скачивания.</p>
 *
 * <p>Позже сюда же придёт языковая модель для выжимки — она делит ту же
 * видеопамять с Whisper и одновременно с ним не помещается.</p>
 *
 * <p>Семафор честный (FIFO): без этого задача могла бы бесконечно уступать
 * очередь другим и не дождаться своего хода.</p>
 */
@Component
@Slf4j
public class GpuLock {

    private final Semaphore permits;

    public GpuLock(@Value("${gpu.max-concurrent:1}") int maxConcurrent) {
        this.permits = new Semaphore(maxConcurrent, true);
        log.info("Пропуск на видеокарту: одновременных задач не больше {}", maxConcurrent);
    }

    /**
     * Занимает видеокарту, при необходимости ожидая освобождения.
     *
     * @param what что именно её займёт — попадёт в лог, чтобы по журналу было
     *             видно, кто держит очередь
     */
    public void acquire(String what) throws InterruptedException {
        if (permits.tryAcquire()) {
            return;
        }
        // Ждём только если реально занято: иначе в логе был бы шум на каждой задаче
        log.info("Видеокарта занята, {} ждёт очереди (перед ним {})",
                what, permits.getQueueLength());
        long startedAt = System.currentTimeMillis();
        permits.acquire();
        log.info("{} дождался видеокарты за {} с",
                what, (System.currentTimeMillis() - startedAt) / 1000);
    }

    public void release() {
        permits.release();
    }

    /** Сколько задач сейчас стоит в очереди за видеокартой — для статуса. */
    public int queueLength() {
        return permits.getQueueLength();
    }
}

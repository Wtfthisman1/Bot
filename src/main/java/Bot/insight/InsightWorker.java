package Bot.insight;

/**
 * Отдельный поток, считающий заказанные обработки.
 *
 * <p>Ответственность: брать заказы из очереди по одному, занимать под них
 * видеокарту и складывать результат. Поток один намеренно: модель и Whisper
 * делят одни 8 ГБ видеопамяти, и вторая обработка параллельно первой означала
 * бы не «вдвое быстрее», а «обе не поместились».</p>
 *
 * <p>Пропуск берётся тот же, что у расшифровок ({@link GpuLock}) — иначе
 * выжимка загружала бы веса ровно тогда, когда Whisper держит память под
 * модель распознавания, и падало бы то одно, то другое. Очередь честная, так
 * что обработка не оттеснит расшифровки: они важнее, за ними человек пришёл.</p>
 *
 * <p>После работы веса выгружаются немедленно: чужая задача не должна ждать,
 * пока истечёт {@code keep_alive} модели, которой уже никто не пользуется.</p>
 */
import Bot.config.Profiles;
import Bot.processing.GpuLock;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Profile(Profiles.HOME)
@Component
@RequiredArgsConstructor
@Slf4j
public class InsightWorker {

    /** Пустая очередь — обычное состояние: заказ делают руками, изредка. */
    @Value("${insight.poll-interval-ms:3000}")
    private long pollIntervalMs;

    private final InsightService insights;
    private final GpuLock gpu;

    private volatile boolean running = true;
    private Thread thread;

    @PostConstruct
    void start() {
        insights.recoverStuck();

        thread = new Thread(this::workLoop, "insight-worker");
        thread.setDaemon(true);
        thread.start();
        log.info("Обработка текста: воркер запущен, опрос каждые {} мс", pollIntervalMs);
    }

    @PreDestroy
    void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
        }
    }

    private void workLoop() {
        while (running) {
            try {
                Optional<InsightService.Order> claimed = insights.claim();
                if (claimed.isEmpty()) {
                    Thread.sleep(pollIntervalMs);
                    continue;
                }
                process(claimed.get());
            } catch (InterruptedException ie) {
                if (!running) {
                    break;   // нормальная остановка
                }
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                // Сорваться может и обращение к базе — тогда пауза важнее всего:
                // иначе цикл закрутится вхолостую и зальёт лог
                log.error("Ошибка обработки текста", e);
                sleepQuietly();
            }
        }
        log.info("Обработка текста: воркер остановлен");
    }

    private void process(InsightService.Order order) throws InterruptedException {
        MDC.put("jobId", order.jobId().toString().substring(0, 8));
        long startedAt = System.currentTimeMillis();
        gpu.acquire("обработка текста");
        try {
            String text = insights.compute(order);
            insights.complete(order.id(), text);
            log.info("Обработка готова за {} с: вид={}",
                    (System.currentTimeMillis() - startedAt) / 1000, order.kind());
        } catch (Exception e) {
            log.error("Не удалось обработать расшифровку: вид={}", order.kind(), e);
            insights.fail(order.id(), "Модель не справилась с этим текстом. Попробуйте ещё раз.");
        } finally {
            // Даже если счёт сорвался, веса уже могли загрузиться в память
            insights.unloadModel();
            gpu.release();
            MDC.remove("jobId");
        }
    }

    private void sleepQuietly() {
        try {
            Thread.sleep(pollIntervalMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}

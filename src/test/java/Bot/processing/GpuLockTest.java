package Bot.processing;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class GpuLockTest {

    /**
     * Главное свойство: второй желающий обязан ждать. Без этого два Whisper
     * стартовали бы разом и второй падал бы с CUDA out of memory.
     */
    @Test
    void secondTaskWaitsUntilFirstReleases() throws Exception {
        GpuLock lock = new GpuLock(1);
        lock.acquire("первая");

        AtomicInteger acquiredBySecond = new AtomicInteger(0);
        CountDownLatch secondStarted = new CountDownLatch(1);
        CountDownLatch secondFinished = new CountDownLatch(1);

        Thread second = new Thread(() -> {
            secondStarted.countDown();
            try {
                lock.acquire("вторая");
                acquiredBySecond.incrementAndGet();
                lock.release();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            secondFinished.countDown();
        });
        second.start();

        assertThat(secondStarted.await(2, TimeUnit.SECONDS)).isTrue();
        // Пропуск занят — вторая не должна пройти, сколько бы ни ждала
        assertThat(secondFinished.await(300, TimeUnit.MILLISECONDS)).isFalse();
        assertThat(acquiredBySecond.get()).isZero();

        lock.release();

        assertThat(secondFinished.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(acquiredBySecond.get()).isEqualTo(1);
    }

    /**
     * Настройка должна работать: на машине с двумя видеокартами разумно
     * разрешить две задачи сразу.
     */
    @Test
    void allowsAsManyTasksAsConfigured() throws Exception {
        GpuLock lock = new GpuLock(2);

        lock.acquire("первая");
        lock.acquire("вторая");

        CountDownLatch thirdFinished = new CountDownLatch(1);
        Thread third = new Thread(() -> {
            try {
                lock.acquire("третья");
                lock.release();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            thirdFinished.countDown();
        });
        third.start();

        assertThat(thirdFinished.await(300, TimeUnit.MILLISECONDS)).isFalse();
        lock.release();
        assertThat(thirdFinished.await(2, TimeUnit.SECONDS)).isTrue();

        lock.release();
    }

    /**
     * Семафор честный, поэтому ожидающие проходят в порядке прихода. Иначе одна
     * задача могла бы бесконечно уступать очередь и не дождаться видеокарты.
     */
    @Test
    void waitersAreServedInArrivalOrder() throws Exception {
        GpuLock lock = new GpuLock(1);
        lock.acquire("держит");

        List<Integer> order = new ArrayList<>();
        CountDownLatch allDone = new CountDownLatch(3);
        List<Thread> waiters = new ArrayList<>();

        for (int i = 1; i <= 3; i++) {
            int n = i;
            Thread t = new Thread(() -> {
                try {
                    lock.acquire("ожидающий " + n);
                    synchronized (order) {
                        order.add(n);
                    }
                    lock.release();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                allDone.countDown();
            });
            waiters.add(t);
            t.start();
            // Даём каждому встать в очередь до появления следующего, иначе
            // «порядок прихода» не определён и проверять было бы нечего
            Thread.sleep(120);
        }

        assertThat(lock.queueLength()).isEqualTo(3);
        lock.release();

        assertThat(allDone.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(order).containsExactly(1, 2, 3);
    }
}

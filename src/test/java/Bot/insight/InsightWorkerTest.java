package Bot.insight;

/**
 * Кому уходит посчитанное.
 *
 * <p>Проверяется не счёт, а развилка после него: заказ из чата обязан получить
 * ответ сообщением, заказ со страницы — не получить ничего. Ошибка здесь тихая
 * и обнаруживается только через живого пользователя, который ждёт выжимку и не
 * дожидается.</p>
 */
import Bot.processing.GpuLock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InsightWorkerTest {

    private static final long CHAT = 99L;

    @Mock private InsightService insights;
    @Mock private InsightDelivery delivery;

    private final GpuLock gpu = new GpuLock(1);

    private InsightWorker worker() {
        return new InsightWorker(insights, delivery, gpu);
    }

    private static InsightService.Order order(Long chatId) {
        return new InsightService.Order(1, UUID.randomUUID(), InsightKind.SUMMARY, 15, null, chatId);
    }

    @Test
    void answerFromChatComesBackToThatChat() throws InterruptedException {
        InsightService.Order order = order(CHAT);
        when(insights.compute(order)).thenReturn("Речь шла о сроках.");

        worker().process(order);

        verify(insights).complete(1, "Речь шла о сроках.");
        verify(delivery).ready(CHAT, InsightKind.SUMMARY, "Речь шла о сроках.");
    }

    /** Заказ со страницы в чат не уходит: там его показывать некому. */
    @Test
    void answerFromPageIsNotSentAnywhere() throws InterruptedException {
        InsightService.Order order = order(null);
        when(insights.compute(order)).thenReturn("Речь шла о сроках.");

        worker().process(order);

        verifyNoInteractions(delivery);
    }

    /** Сорвавшийся счёт объясняется тому же человеку, а не только журналу. */
    @Test
    void failureIsExplainedInTheChat() throws InterruptedException {
        InsightService.Order order = order(CHAT);
        when(insights.compute(order)).thenThrow(new IllegalStateException("модель молчит"));

        worker().process(order);

        verify(insights).fail(eq(1L), any());
        verify(delivery).failed(eq(CHAT), eq(InsightKind.SUMMARY), any());
    }

    /**
     * Неотправленное сообщение не должно съедать пропуск на видеокарту: после
     * него ещё считать расшифровки.
     */
    @Test
    void undeliveredMessageStillReleasesTheCard() throws InterruptedException {
        InsightService.Order order = order(CHAT);
        when(insights.compute(order)).thenReturn("Речь шла о сроках.");
        doThrow(new RuntimeException("Telegram недоступен"))
                .when(delivery).ready(anyLong(), any(), any());

        worker().process(order);

        verify(insights).unloadModel();
        // Пропуск свободен: следующая задача возьмёт его не ожидая. С таймаутом,
        // потому что потерянный пропуск иначе не провалит тест, а подвесит его
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            gpu.acquire("проверка");
            gpu.release();
        });
    }
}

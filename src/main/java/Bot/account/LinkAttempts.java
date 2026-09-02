package Bot.account;

/**
 * Сколько раз один чат может ошибиться кодом привязки.
 *
 * <p>Ответственность: не дать подбирать коды привязки командой {@code /link}.
 * Код — шесть знаков из алфавита в 32 символа, и попаданием он отдаёт чужую
 * переписку целиком: чат, привязанный к аккаунту, входит в него по
 * {@code /login} как в свой. Перебором это ловится плохо (миллиард вариантов
 * при пятнадцатиминутной жизни кода), но ограничения не было вовсе — а на
 * стороне бота их не поставит никто: команды приходят из Telegram, не через
 * nginx.</p>
 *
 * <p>Счёт по чату, а не по адресу: адреса у отправителя команды нет вовсе.
 * Хранится в памяти, как и {@code LoginAttempts}: перезапуск сбрасывает
 * счётчики, и это дешевле, чем писать в базу на каждую ошибку.</p>
 */
import Bot.config.Profiles;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Profile(Profiles.HOME)
@Component
@Slf4j
public class LinkAttempts {

    /** Столько промахов подряд терпим: живой человек копирует код, а не гадает. */
    private static final int MAX_FAILURES = 5;

    private static final Duration WINDOW = Duration.ofMinutes(15);

    private final Map<Long, Counter> counters = new ConcurrentHashMap<>();

    /** Можно ли пустить эту попытку. */
    public boolean allows(long chatId) {
        Counter counter = counters.get(chatId);
        return counter == null || !counter.blocked();
    }

    /** Промах: следующий приблизит запрет. */
    public void failed(long chatId) {
        Counter counter = counters.compute(chatId,
                (id, existing) -> existing == null || existing.expired() ? new Counter() : existing);
        if (counter.increment() == MAX_FAILURES) {
            log.warn("Слишком много неверных кодов привязки: chatId={}", chatId);
        }
    }

    /** Код подошёл — придерживать этот чат больше незачем. */
    public void succeeded(long chatId) {
        counters.remove(chatId);
    }

    /** Чистка: без неё карта росла бы на каждый новый чат. */
    @Scheduled(fixedRate = 10 * 60 * 1000)
    void purge() {
        counters.values().removeIf(Counter::expired);
    }

    /** Счётчик промахов с моментом первого из них. */
    private static final class Counter {
        private final Instant startedAt = Instant.now();
        private final AtomicInteger failures = new AtomicInteger();

        int increment() {
            return failures.incrementAndGet();
        }

        boolean blocked() {
            return !expired() && failures.get() >= MAX_FAILURES;
        }

        boolean expired() {
            return startedAt.plus(WINDOW).isBefore(Instant.now());
        }
    }
}

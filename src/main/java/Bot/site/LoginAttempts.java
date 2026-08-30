package Bot.site;

/**
 * Сколько раз подряд с одного адреса можно ошибиться дверью.
 *
 * <p>Ответственность: не дать перебирать пароли и штамповать регистрации. До
 * этого ограничения не было нигде — ни в приложении, ни в nginx, — и подбор
 * пароля упирался только в скорость bcrypt.</p>
 *
 * <p>Считается по адресу клиента, а не по почте: перебирают обычно один
 * известный адрес многими паролями, но бывает и наоборот — один пароль по
 * списку почт. Счётчик по источнику ловит оба случая.</p>
 *
 * <p>Окно скользящее и в памяти: перезапуск сбрасывает счётчики, и это
 * осознанно. Хранить попытки в базе значило бы писать в неё с каждого
 * неудачного входа — удобная мишень для того же перебора.</p>
 */
import Bot.config.Profiles;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Profile(Profiles.HOME)
@Component
@Slf4j
public class LoginAttempts {

    /** Сколько неудач подряд терпим. */
    @Value("${login.max-attempts:10}")
    private int maxAttempts;

    /** За какое время они должны накопиться и через сколько забываются. */
    @Value("${login.window-minutes:15}")
    private int windowMinutes;

    private final Map<String, Counter> counters = new ConcurrentHashMap<>();

    /** Можно ли пустить эту попытку. */
    public boolean allows(HttpServletRequest request) {
        Counter counter = counters.get(keyOf(request));
        return counter == null || !counter.blocked(window(), maxAttempts);
    }

    /** Неудача: следующая попытка с этого адреса приблизит запрет. */
    public void failed(HttpServletRequest request) {
        String key = keyOf(request);
        Counter counter = counters.compute(key, (k, existing) ->
                existing == null || existing.expired(window()) ? new Counter() : existing);
        int now = counter.increment();
        if (now == maxAttempts) {
            log.warn("Слишком много неудачных попыток входа с {} — адрес придержан на {} минут",
                    key, windowMinutes);
        }
    }

    /** Удача обнуляет счётчик: человек вспомнил пароль, придерживать его незачем. */
    public void succeeded(HttpServletRequest request) {
        counters.remove(keyOf(request));
    }

    /** Сколько ждать — это и есть весь текст отказа. */
    public String refusal() {
        return "Слишком много попыток. Подождите " + windowMinutes + " минут и попробуйте снова.";
    }

    private Duration window() {
        return Duration.ofMinutes(windowMinutes);
    }

    /**
     * Кто стучится.
     *
     * <p>Без заголовка все запросы выглядели бы приходящими с одного адреса
     * туннеля, и первый же перебор запер бы вход всем сразу.</p>
     *
     * <p>Берём именно {@code X-Real-IP}: nginx его <b>перезаписывает</b>
     * ({@code proxy_set_header X-Real-IP $remote_addr}), поэтому подделать его
     * нельзя. {@code X-Forwarded-For} для этого не годится — nginx лишь
     * дописывает настоящий адрес в конец списка, а начало заголовка присылает
     * сам клиент: считая ключом первый элемент, ограничитель обходился бы
     * сменой одной строки в запросе.</p>
     */
    private static String keyOf(HttpServletRequest request) {
        String real = request.getHeader("X-Real-IP");
        if (real != null && !real.isBlank()) {
            return real.trim();
        }
        return request.getRemoteAddr();
    }

    /** Чистка: без неё карта росла бы на каждый новый адрес. */
    @Scheduled(fixedRate = 10 * 60 * 1000)
    void purge() {
        counters.entrySet().removeIf(e -> e.getValue().expired(window()));
    }

    /** Счётчик неудач с моментом первой из них. */
    private static final class Counter {
        private final Instant startedAt = Instant.now();
        private int failures;

        synchronized int increment() {
            return ++failures;
        }

        synchronized boolean blocked(Duration window, int max) {
            return !expired(window) && failures >= max;
        }

        synchronized boolean expired(Duration window) {
            return startedAt.plus(window).isBefore(Instant.now());
        }
    }
}

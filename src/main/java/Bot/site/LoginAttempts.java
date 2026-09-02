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
 *
 * <p>У регистрации счёт отдельный и более строгий. Причина не в паролях: форма
 * регистрации честно отвечает «на эту почту аккаунт уже заведён», и по этому
 * ответу можно перебрать список адресов и узнать, кто здесь есть. Убрать сам
 * ответ нечем — без почтовой службы человеку иначе не объяснить, почему он не
 * регистрируется, — поэтому перебору ограничивается скорость: пять попыток в
 * час с адреса делают проверку сотни адресов работой на сутки.</p>
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

    /** Сколько регистраций с одного адреса терпим — вместе с неудачными. */
    @Value("${register.max-attempts:5}")
    private int maxRegistrations;

    /** И за какое время. Час, а не 15 минут: перебор адресов никуда не спешит. */
    @Value("${register.window-minutes:60}")
    private int registrationWindowMinutes;

    private final Map<String, Counter> counters = new ConcurrentHashMap<>();

    /** Метка второго счётчика: у регистрации свой предел и своё окно. */
    private static final String REGISTRATION = "register|";

    /** Можно ли пустить эту попытку. */
    public boolean allows(HttpServletRequest request) {
        Counter counter = counters.get(keyOf(request));
        return counter == null || !counter.blocked(window(), maxAttempts);
    }

    /**
     * То же для регистрации — предел свой.
     *
     * <p>Считается и удачная, и неудачная: перебор адресов состоит как раз из
     * неудачных, а конвейер аккаунтов — из удачных.</p>
     */
    public boolean allowsRegistration(HttpServletRequest request) {
        Counter counter = counters.get(REGISTRATION + keyOf(request));
        return counter == null || !counter.blocked(registrationWindow(), maxRegistrations);
    }

    /** Попытка регистрации израсходована — чем бы она ни кончилась. */
    public void spendRegistration(HttpServletRequest request) {
        String key = REGISTRATION + keyOf(request);
        Counter counter = counters.compute(key, (k, existing) ->
                existing == null || existing.expired(registrationWindow()) ? new Counter() : existing);
        int now = counter.increment();
        if (now == maxRegistrations) {
            log.warn("Слишком много регистраций с {} — адрес придержан на {} минут",
                    keyOf(request), registrationWindowMinutes);
        }
    }

    /** Сколько ждать после исчерпанных регистраций. */
    public String registrationRefusal() {
        return "Слишком много попыток регистрации. Подождите "
                + registrationWindowMinutes + " минут и попробуйте снова.";
    }

    /** Неудача: следующая попытка с этого адреса приблизит запрет. */
    public void failed(HttpServletRequest request) {
        spend(request);
    }

    /**
     * Попытка засчитана, хотя ничего и не сломалось.
     *
     * <p>Нужно регистрации: неудачные она считала, а удачные — нет, и один
     * адрес мог штамповать аккаунты без счёта. Каждый новый аккаунт — это ещё
     * три бесплатные расшифровки, то есть чужое время на видеокарте; квота по
     * аккаунту не значит ничего, пока аккаунты бесплатны и бесконечны.</p>
     */
    public void spend(HttpServletRequest request) {
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

    private Duration registrationWindow() {
        return Duration.ofMinutes(registrationWindowMinutes);
    }

    /** Кто стучится — по тому же адресу, что показывает сообщение о входе. */
    private static String keyOf(HttpServletRequest request) {
        return ClientAddress.of(request);
    }

    /**
     * Чистка: без неё карта росла бы на каждый новый адрес.
     *
     * <p>Записи регистрации живут дольше, поэтому их срок считается своим
     * окном: общая мерка стирала бы их раньше времени и обнуляла счёт.</p>
     */
    @Scheduled(fixedRate = 10 * 60 * 1000)
    void purge() {
        counters.entrySet().removeIf(e -> e.getValue().expired(
                e.getKey().startsWith(REGISTRATION) ? registrationWindow() : window()));
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

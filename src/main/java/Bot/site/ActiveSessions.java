package Bot.site;

/**
 * Живые сессии сайта — чтобы их можно было закрыть.
 *
 * <p>Ответственность: дать человеку способ выкинуть из аккаунта всех, кроме
 * себя. Без него сообщение «в ваш аккаунт вошли» было бы пустым звуком: сессия
 * живёт месяц, и тот, кто открыл пересланную ссылку входа, сидел бы в кабинете
 * всё это время.</p>
 *
 * <p>Хранится в памяти, и это не упрощение: сами сессии Tomcat тоже лежат в
 * памяти этого процесса. Перезапуск закрывает и их, и этот список разом —
 * состояния, которое можно потерять по отдельности, здесь нет.</p>
 *
 * <p>Слушателя сессий нет намеренно: мёртвая сессия узнаётся при первом же
 * обращении к ней ({@code getId} бросает исключение), поэтому список чистится
 * по ходу дела и раз в час — вместо регистрации листенера в контейнере.</p>
 */
import Bot.config.Profiles;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Profile(Profiles.HOME)
@Component
@Slf4j
public class ActiveSessions {

    /** Идентификатор сессии → чья она и она сама. */
    private final Map<String, Bound> sessions = new ConcurrentHashMap<>();

    /** Запоминает вошедшего. Зовётся после смены идентификатора сессии. */
    public void remember(UUID accountId, HttpSession session) {
        sessions.put(session.getId(), new Bound(accountId, session));
    }

    /**
     * Закрывает все сессии аккаунта, кроме текущей.
     *
     * @param keepId сессия, из которой нажали кнопку, — её оставляем
     * @return сколько закрыли; ноль означает «больше нигде и не входили»
     */
    public int closeOthers(UUID accountId, String keepId) {
        int closed = 0;
        for (Bound bound : List.copyOf(sessions.values())) {
            if (!accountId.equals(bound.accountId())) {
                continue;
            }
            try {
                String id = bound.session().getId();
                if (id.equals(keepId)) {
                    continue;
                }
                bound.session().invalidate();
                sessions.remove(id);
                closed++;
            } catch (IllegalStateException e) {
                // Сессия умерла сама — по таймауту или выходом; просто забываем
                forget(bound);
            }
        }
        if (closed > 0) {
            log.info("Закрыты чужие сессии аккаунта {}: {}", accountId, closed);
        }
        return closed;
    }

    /** Протухшие записи: без чистки карта росла бы на каждый вход. */
    @Scheduled(fixedRate = 60 * 60 * 1000)
    void purgeDead() {
        for (Bound bound : List.copyOf(sessions.values())) {
            try {
                bound.session().getId();
            } catch (IllegalStateException e) {
                forget(bound);
            }
        }
    }

    /** Мёртвая сессия своего идентификатора уже не отдаёт — ищем по значению. */
    private void forget(Bound bound) {
        sessions.entrySet().removeIf(entry -> entry.getValue() == bound);
    }

    private record Bound(UUID accountId, HttpSession session) {}
}

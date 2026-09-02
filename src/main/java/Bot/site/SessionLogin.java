package Bot.site;

/**
 * Вход в сессию: один способ на все двери.
 *
 * <p>Ответственность: положить аккаунт в {@code SecurityContext} и сохранить
 * его в сессии. Дверей у сайта три — пароль, Telegram и Google, — а вошедший
 * должен получаться одинаковый: иначе страницы пришлось бы писать под каждый
 * тип входа отдельно.</p>
 *
 * <p>Идентификатор сессии меняется при входе. Иначе выданный до входа
 * (например, подсунутый ссылкой) идентификатор оставался бы действительным
 * и после — это и есть фиксация сессии.</p>

 * <p>Каждая заведённая сессия попадает в {@link ActiveSessions}: без списка
 * живых сессий кнопка «выйти на всех устройствах» была бы невыполнимым
 * обещанием, а сообщение о чужом входе — пустым звуком.</p>
 *
 * <p>Какой дверью вошли, запоминается в сессии. Это нужно ровно в одном месте —
 * при смене пароля: вошедший паролем обязан его повторить, а вошедший через
 * бота, Telegram или Google задаёт новый, не зная старого. Это и есть
 * восстановление пароля, которого иначе нет вовсе: письма мы слать не умеем.</p>
 */
import Bot.account.AccountService.Account;
import Bot.config.Profiles;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Profile(Profiles.HOME)
@Component
@RequiredArgsConstructor
@Slf4j
public class SessionLogin {

    /** Ключ в сессии: какой дверью вошли. */
    static final String DOOR_ATTRIBUTE = "login.door";

    /** Двери сайта. Пароль стоит особняком: только он знает сам человек. */
    public enum Door { PASSWORD, TELEGRAM, GOOGLE, BOT_LINK }

    private final SecurityContextRepository contexts = new HttpSessionSecurityContextRepository();

    private final ActiveSessions activeSessions;

    public void signIn(HttpServletRequest request, HttpServletResponse response,
                       Account account, Door door) {
        if (request.getSession(false) != null) {
            request.changeSessionId();
        } else {
            request.getSession(true);
        }

        AccountPrincipal principal = new AccountPrincipal(account.id(), account.title());
        UsernamePasswordAuthenticationToken token = UsernamePasswordAuthenticationToken.authenticated(
                principal, null, AuthorityUtils.createAuthorityList("ROLE_USER"));

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(token);
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
        request.getSession(true).setAttribute(DOOR_ATTRIBUTE, door);
        activeSessions.remember(account.id(), request.getSession(true));

        log.info("Вход на сайт: аккаунт={}, дверь={}", account.id(), door);
    }

    /**
     * Какой дверью вошли в эту сессию.
     *
     * <p>Пустой ответ означает «неизвестно» — так выглядят сессии, заведённые
     * до появления этой отметки. Спрашивающий обязан толковать неизвестность
     * строго, как пароль: подарить смену пароля без старого тому, про кого
     * ничего не известно, — это не восстановление, а обход.</p>
     */
    public static java.util.Optional<Door> doorOf(HttpServletRequest request) {
        jakarta.servlet.http.HttpSession session = request.getSession(false);
        if (session == null) {
            return java.util.Optional.empty();
        }
        Object value = session.getAttribute(DOOR_ATTRIBUTE);
        return value instanceof Door door ? java.util.Optional.of(door) : java.util.Optional.empty();
    }
}

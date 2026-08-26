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
 */
import Bot.account.AccountService.Account;
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
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class SessionLogin {

    private final SecurityContextRepository contexts = new HttpSessionSecurityContextRepository();

    public void signIn(HttpServletRequest request, HttpServletResponse response, Account account) {
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

        log.info("Вход на сайт: аккаунт={}", account.id());
    }
}

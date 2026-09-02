package Bot.site;

/**
 * Google подтвердил, кто это, — дальше вход как у всех.
 *
 * <p>Ответственность: превратить пользователя OAuth2 в наш аккаунт и положить
 * его в сессию тем же способом, что и вход по паролю. Без этого шага в сессии
 * лежал бы {@code OidcUser}, и каждая страница знала бы про два разных типа
 * вошедшего.</p>
 *
 * <p>Ключ поиска — {@code sub}, а не почта: почту в Google-аккаунте меняют, и
 * привязка по ней однажды увела бы человека в чужие задачи.</p>
 */
import Bot.account.AccountService;
import Bot.account.IdentityProvider;
import Bot.config.Profiles;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Profile(Profiles.HOME)
@Component
@RequiredArgsConstructor
@Slf4j
public class GoogleLoginSuccessHandler implements AuthenticationSuccessHandler {

    private final AccountService accounts;
    private final SessionLogin sessionLogin;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        OAuth2User user = (OAuth2User) authentication.getPrincipal();
        String subject = user.getName();   // sub из id-токена
        String email = user.getAttribute("email");
        String name = user.getAttribute("name");

        AccountService.Account account = accounts.findOrCreateByIdentity(
                IdentityProvider.GOOGLE, subject, name, email);

        sessionLogin.signIn(request, response, account, SessionLogin.Door.GOOGLE);
        response.sendRedirect("/cabinet");
    }
}

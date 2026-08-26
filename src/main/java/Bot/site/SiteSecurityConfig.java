package Bot.site;

/**
 * Кого куда пускать на домашней половине.
 *
 * <p>Ответственность: одно место, где написано, что закрыто, а что открыто.
 * Spring Security по умолчанию закрывает всё подряд, а половина адресов бота
 * существует именно для того, чтобы по ним ходили без всякого входа: ссылку на
 * скачивание человек открывает из чата, форму загрузки — по одноразовому
 * токену, а {@code /internal} — это бот с VPS, у которого есть общий ключ.</p>
 *
 * <p>Защита у каждого из них своя и до сих пор работала без Spring Security:
 * непредсказуемый токен в ссылке, одноразовый токен формы, ключ в заголовке.
 * Поэтому здесь они открыты явно — закрыть их сессией значило бы сломать всё,
 * что работает сегодня, ничего не добавив.</p>
 *
 * <p>CSRF: включён для страниц сайта, но выключен для {@code /upload} и
 * {@code /internal}. Форма загрузки — статический HTML без токена, а бот на VPS
 * ходит машинным запросом; сессии у обоих нет, и подделывать в ней нечего.</p>
 */
import Bot.config.Profiles;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

@Profile(Profiles.HOME)
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
@Slf4j
public class SiteSecurityConfig {

    /** Адреса, которые работали до появления сайта и должны работать дальше. */
    private static final String[] OPEN = {
            "/", "/login", "/login/**", "/oauth2/**", "/register", "/auth/**",
            "/help", "/privacy", "/terms",
            "/download/**", "/upload/**", "/internal/**",
            "/css/**", "/js/**", "/favicon.ico", "/error"
    };

    private final GoogleLoginSuccessHandler googleLogin;

    @Bean
    SecurityFilterChain siteSecurity(HttpSecurity http,
                                     ObjectProvider<ClientRegistrationRepository> googleClients)
            throws Exception {

        http
                .csrf(csrf -> csrf.ignoringRequestMatchers(
                        new AntPathRequestMatcher("/internal/**"),
                        new AntPathRequestMatcher("/upload/**")))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(OPEN).permitAll()
                        .anyRequest().authenticated())
                // Своей формы входа у Spring Security здесь нет: вход по паролю,
                // через Telegram и через Google одинаково кладут аккаунт в сессию
                // сами (см. SessionLogin), и разводить два механизма незачем
                .exceptionHandling(handling -> handling
                        .defaultAuthenticationEntryPointFor(
                                (request, response, e) -> response.sendRedirect("/login"),
                                new AntPathRequestMatcher("/**")))
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/")
                        .deleteCookies("JSESSIONID"));

        // Вход через Google включается только тогда, когда заданы ключи
        // приложения: без них ClientRegistrationRepository не создаётся вовсе,
        // и обращение к oauth2Login() уронило бы контекст на старте
        if (googleClients.getIfAvailable() != null) {
            http.oauth2Login(oauth -> oauth
                    .loginPage("/login")
                    .successHandler(googleLogin));
            log.info("Вход через Google включён");
        } else {
            log.info("Вход через Google выключен: не заданы GOOGLE_CLIENT_ID и GOOGLE_CLIENT_SECRET");
        }

        return http.build();
    }
}

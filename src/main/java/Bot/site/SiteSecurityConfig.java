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
 * ходит машинным запросом; сессии у обоих нет, и подделывать в ней нечего.
 * Для формы загрузки это к тому же ничего не дало бы: вся её защита — секретный
 * одноразовый токен в адресе, и тот, кто его знает, просто открыл бы саму
 * форму.</p>
 *
 * <p>Заголовки: {@code nosniff}, {@code X-Frame-Options} и HSTS Spring Security
 * ставит сам, а вот политику содержимого — нет, и она дописана здесь. На
 * страницах кабинета показывается текст расшифровок и ответы модели; выводятся
 * они через {@code th:text}, то есть экранированными (сырого {@code th:utext}
 * в шаблонах нет ни одного), но политика — это второй слой ровно на тот
 * случай, если однажды появится первый недосмотр. Политик две: у формы
 * загрузки стили и скрипт лежат внутри самого файла, и общая политика её
 * попросту выключила бы.</p>
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
import org.springframework.security.web.header.writers.DelegatingRequestMatcherHeaderWriter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
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

    /** Адреса формы загрузки: у неё своя политика — см. {@link #UPLOAD_CSP}. */
    private static final String UPLOAD_FORM = "/upload/**";

    /**
     * Политика для страниц сайта.
     *
     * <p>Свои стили и скрипты плюс виджет входа Telegram — больше страницы не
     * грузят ничего. Ни одного тега {@code <script>} или атрибута
     * {@code style=} внутри разметки нет, поэтому {@code 'unsafe-inline'} не
     * нужен ни там, ни там.</p>
     */
    private static final String SITE_CSP = String.join("; ",
            "default-src 'self'",
            "script-src 'self' https://telegram.org",
            "style-src 'self'",
            // Значок страницы — data:-адрес прямо в разметке
            "img-src 'self' data:",
            // Запись для плеера отдаёт сам сайт
            "media-src 'self'",
            // Виджет входа рисуется во фрейме с oauth.telegram.org
            "frame-src https://oauth.telegram.org",
            "connect-src 'self'",
            "form-action 'self'",
            "base-uri 'self'",
            "object-src 'none'",
            "frame-ancestors 'none'");

    /**
     * Политика для формы загрузки.
     *
     * <p>Форма — один самодостаточный файл со стилями и скриптом внутри: её
     * открывают по одноразовой ссылке из чата, и лишний круг за css на
     * мобильном канале ей ни к чему. Поэтому здесь {@code 'unsafe-inline'} —
     * без него форма просто не работает.</p>
     *
     * <p>Остальное закрыто так же, как у страниц сайта: снаружи форма не
     * грузит ничего вовсе.</p>
     */
    private static final String UPLOAD_CSP = String.join("; ",
            "default-src 'self'",
            "script-src 'self' 'unsafe-inline'",
            "style-src 'self' 'unsafe-inline'",
            "img-src 'self' data:",
            "connect-src 'self'",
            "form-action 'self'",
            "base-uri 'self'",
            "object-src 'none'",
            "frame-ancestors 'none'");

    private final GoogleLoginSuccessHandler googleLogin;

    /** Заголовок политики, который ставится только на подходящие адреса. */
    private static DelegatingRequestMatcherHeaderWriter policyFor(RequestMatcher matcher,
                                                                  String policy) {
        return new DelegatingRequestMatcherHeaderWriter(matcher,
                new StaticHeadersWriter("Content-Security-Policy", policy));
    }

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
                        .deleteCookies("JSESSIONID"))
                .headers(headers -> headers
                        // Политика разная для страниц сайта и для формы
                        // загрузки, поэтому пишется не одним вызовом, а двумя
                        // писателями заголовка с условием
                        .addHeaderWriter(policyFor(new AntPathRequestMatcher(UPLOAD_FORM), UPLOAD_CSP))
                        .addHeaderWriter(policyFor(
                                new NegatedRequestMatcher(new AntPathRequestMatcher(UPLOAD_FORM)),
                                SITE_CSP))
                        // Умолчание браузеров, названное явно: наружу уходит
                        // только имя домена, но не адрес страницы — а в адресах
                        // здесь ездят одноразовые токены
                        .referrerPolicy(referrer -> referrer.policy(
                                ReferrerPolicyHeaderWriter.ReferrerPolicy
                                        .STRICT_ORIGIN_WHEN_CROSS_ORIGIN)));

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

package Bot.site;

/**
 * Половина без дома: закрывать нечего.
 *
 * <p>На VPS живёт только приём запросов — long polling Telegram и спул. HTTP
 * там наружу не смотрит вовсе: nginx проксирует домой, а сам процесс бота
 * никаких страниц не отдаёт. Без этого правила Spring Security закрыл бы
 * пустоту паролем из журнала запуска — и первый же {@code /actuator/health}
 * с самой машины отвечал бы 401.</p>
 */
import Bot.config.Profiles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Profile("!" + Profiles.HOME)
@Configuration
@EnableWebSecurity
public class BotHalfSecurityConfig {

    @Bean
    SecurityFilterChain openEverything(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
    }
}

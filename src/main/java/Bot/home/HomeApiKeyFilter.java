package Bot.home;

/**
 * Общий ключ на внутренних адресах дома.
 *
 * <p>Ответственность: не пустить к {@code /internal/**} никого, кроме бота.
 * Туннель WireGuard уже отсекает интернет, но ключ нужен вторым слоем: в
 * домашней сети есть и другие машины, а адрес {@code /internal/home/jobs/link}
 * ставит задачи от имени любого владельца.</p>
 *
 * <p>Пустой ключ означает «внутрь никого»: когда бот и дом в одном процессе,
 * HTTP не участвует вовсе, и открытый нараспашку адрес был бы чистым риском
 * без единого сценария применения.</p>
 */
import Bot.config.Profiles;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Profile(Profiles.HOME)
@Component
@Slf4j
public class HomeApiKeyFilter extends OncePerRequestFilter {

    @Value("${home.api.key:}")
    private String expectedKey;

    /**
     * Фильтр висит на всех запросах, но касается только внутренних адресов.
     *
     * <p>Сравнение без учёта регистра: сам по себе {@code /INTERNAL/...} до
     * контроллера не доходит (Spring сопоставляет пути с учётом регистра, и это
     * будет 404), но правило «что закрыто» не должно держаться на совпадении
     * двух разных механизмов. Ошибиться здесь можно только в одну сторону —
     * лишний раз спросить ключ.</p>
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri == null || !uri.toLowerCase(java.util.Locale.ROOT).startsWith("/internal/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!matches(request.getHeader(HomeProtocol.KEY_HEADER))) {
            log.warn("Отклонён запрос к внутреннему адресу {} с {}",
                    request.getRequestURI(), request.getRemoteAddr());
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        chain.doFilter(request, response);
    }

    /** Сравнение постоянного времени: по времени ответа ключ подбирают побайтно. */
    private boolean matches(String provided) {
        if (expectedKey == null || expectedKey.isBlank() || provided == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expectedKey.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8));
    }
}

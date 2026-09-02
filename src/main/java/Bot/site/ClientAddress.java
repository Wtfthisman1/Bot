package Bot.site;

/**
 * Откуда пришёл запрос.
 *
 * <p>Ответственность: одно место, где написано, какому заголовку мы верим.
 * Адрес нужен двоим — ограничителю попыток входа и сообщению «вы вошли», — и
 * разъехаться эти два ответа не должны.</p>
 *
 * <p>Берём именно {@code X-Real-IP}: nginx его <b>перезаписывает</b>
 * ({@code proxy_set_header X-Real-IP $remote_addr}), поэтому подделать его
 * нельзя. {@code X-Forwarded-For} для этого не годится — nginx лишь дописывает
 * настоящий адрес в конец списка, а начало заголовка присылает сам клиент:
 * считая первым элементом ключ, ограничитель обходился бы сменой одной строки
 * в запросе.</p>
 *
 * <p>Всё это верно ровно до тех пор, пока к приложению нельзя обратиться мимо
 * nginx. Поэтому дома оно слушает адрес туннеля, а не все интерфейсы — см.
 * {@code SERVER_ADDRESS} и предупреждение в {@code StartupLogger}.</p>
 */
import jakarta.servlet.http.HttpServletRequest;

final class ClientAddress {

    private ClientAddress() {
    }

    static String of(HttpServletRequest request) {
        String real = request.getHeader("X-Real-IP");
        if (real != null && !real.isBlank()) {
            return real.trim();
        }
        return request.getRemoteAddr();
    }
}

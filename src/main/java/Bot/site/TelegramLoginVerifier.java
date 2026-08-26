package Bot.site;

/**
 * Проверка того, что данные о входе действительно пришли от Telegram.
 *
 * <p>Виджет входа отдаёт браузеру набор полей и подпись. Полям верить нельзя:
 * их видно в адресной строке, и подставить туда чужой {@code id} — дело одной
 * минуты. Подпись считается общим секретом, который есть только у Telegram и у
 * нас, — токеном бота, поэтому подделать её без токена нельзя.</p>
 *
 * <p>Схема из документации Telegram: ключ — {@code SHA256(токен бота)},
 * подпись — HMAC-SHA256 от строки «поле=значение», отсортированной по имени
 * поля и склеенной переводами строк.</p>
 *
 * <p>Срок годности проверяется отдельно: подпись остаётся верной вечно, и без
 * ограничения по времени однажды перехваченная ссылка пускала бы в аккаунт
 * когда угодно.</p>
 */
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

@Component
@Slf4j
public class TelegramLoginVerifier {

    /** Столько живёт подпись виджета. День — с запасом на «открыл и отвлёкся». */
    private static final Duration MAX_AGE = Duration.ofDays(1);

    private final byte[] secretKey;

    public TelegramLoginVerifier(@Value("${bot.key}") String botToken) throws Exception {
        this.secretKey = MessageDigest.getInstance("SHA-256")
                .digest(botToken.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Возвращает данные вошедшего, если подпись верна и не протухла.
     *
     * <p>Пустой ответ — это отказ во входе; различать «подпись не сошлась» и
     * «данные устарели» человеку незачем, а в журнале причина есть.</p>
     */
    public Optional<TelegramUser> verify(Map<String, String> params) {
        String hash = params.get("hash");
        String id = params.get("id");
        if (hash == null || id == null) {
            return Optional.empty();
        }

        Map<String, String> fields = new TreeMap<>(params);
        fields.remove("hash");

        StringBuilder checkString = new StringBuilder();
        for (Map.Entry<String, String> field : fields.entrySet()) {
            if (!checkString.isEmpty()) {
                checkString.append('\n');
            }
            checkString.append(field.getKey()).append('=').append(field.getValue());
        }

        String expected = hmacHex(checkString.toString());
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                hash.toLowerCase().getBytes(StandardCharsets.UTF_8))) {
            log.warn("Вход через Telegram отклонён: подпись не сошлась");
            return Optional.empty();
        }

        String authDate = params.get("auth_date");
        if (authDate == null || Instant.ofEpochSecond(Long.parseLong(authDate))
                .isBefore(Instant.now().minus(MAX_AGE))) {
            log.warn("Вход через Telegram отклонён: данные устарели");
            return Optional.empty();
        }

        String name = String.join(" ",
                        params.getOrDefault("first_name", ""),
                        params.getOrDefault("last_name", ""))
                .trim();
        return Optional.of(new TelegramUser(id,
                name.isBlank() ? params.get("username") : name));
    }

    private String hmacHex(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secretKey, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось посчитать подпись входа Telegram", e);
        }
    }

    /** Кто вошёл: id пользователя Telegram и как его зовут. */
    public record TelegramUser(String id, String name) {}
}

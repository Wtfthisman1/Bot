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
 *
 * <p><b>Почему пять минут, а не сутки.</b> Виджет возвращает подпись в строке
 * запроса: {@code /auth/telegram?id=…&hash=…}. Такой адрес целиком оседает в
 * истории браузера и в журнале доступа nginx — то есть суточная подпись была
 * готовым пропуском в аккаунт для всякого, кто до этого журнала доберётся.
 * Пять минут — это «нажал кнопку и вернулся», больше живому входу не нужно.</p>
 *
 * <p>И тем же ответом закрыт повтор: подпись, по которой уже входили,
 * запоминается до конца своего срока. Иначе одну и ту же ссылку можно было бы
 * открыть дважды — из истории браузера на чужом компьютере, например.</p>
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
import java.util.concurrent.ConcurrentHashMap;

@Component
@Slf4j
public class TelegramLoginVerifier {

    /** Столько живёт подпись виджета: ровно на «нажал кнопку и вернулся». */
    private static final Duration MAX_AGE = Duration.ofMinutes(5);

    /**
     * Подписи, по которым уже входили, — до конца их срока.
     *
     * <p>В памяти, а не в базе: живут они пять минут, а перезапуск и так рвёт
     * все сессии сайта — терять тут нечего.</p>
     */
    private final Map<String, Instant> used = new ConcurrentHashMap<>();

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

        // Разбор в try: нечисловой auth_date — это подделка, и отвечать на неё
        // надо отказом, а не пятисоткой из недр парсера
        Instant signedAt;
        try {
            signedAt = Instant.ofEpochSecond(Long.parseLong(params.getOrDefault("auth_date", "")));
        } catch (NumberFormatException | ArithmeticException e) {
            log.warn("Вход через Telegram отклонён: неразбираемый auth_date");
            return Optional.empty();
        }
        if (signedAt.isBefore(Instant.now().minus(MAX_AGE))) {
            log.warn("Вход через Telegram отклонён: данные устарели");
            return Optional.empty();
        }

        // Заодно выкидываем всё, что уже не могло бы пройти проверку выше
        Instant edge = Instant.now().minus(MAX_AGE);
        used.values().removeIf(when -> when.isBefore(edge));
        if (used.putIfAbsent(hash.toLowerCase(), Instant.now()) != null) {
            log.warn("Вход через Telegram отклонён: подпись уже была использована");
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

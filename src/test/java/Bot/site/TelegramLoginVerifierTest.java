package Bot.site;

/**
 * Подпись виджета Telegram: без токена бота её не подделать, а подделанное
 * поле и просроченные данные во вход не пускают.
 */
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

class TelegramLoginVerifierTest {

    private static final String TOKEN = "111:TEST_TOKEN_NOT_REAL";

    private final TelegramLoginVerifier verifier = new TelegramLoginVerifier(TOKEN);

    TelegramLoginVerifierTest() throws Exception {
    }

    @Test
    void signedDataIsAccepted() {
        Map<String, String> params = signed(Instant.now());

        assertThat(verifier.verify(params))
                .map(TelegramLoginVerifier.TelegramUser::id)
                .contains("4242");
    }

    /** Подставить чужой id — самое очевидное, что придёт в голову. */
    @Test
    void tamperedFieldIsRejected() {
        Map<String, String> params = signed(Instant.now());
        params.put("id", "1");

        assertThat(verifier.verify(params)).isEmpty();
    }

    @Test
    void signatureFromAnotherTokenIsRejected() throws Exception {
        TelegramLoginVerifier other = new TelegramLoginVerifier("222:ANOTHER_TOKEN");

        assertThat(other.verify(signed(Instant.now()))).isEmpty();
    }

    /** Подпись верна вечно — значит, срок годности надо проверять отдельно. */
    @Test
    void staleDataIsRejected() {
        assertThat(verifier.verify(signed(Instant.now().minus(Duration.ofDays(2))))).isEmpty();
    }

    @Test
    void dataWithoutSignatureIsRejected() {
        assertThat(verifier.verify(Map.of("id", "4242"))).isEmpty();
    }

    /* ───────── helpers ───────── */

    /** Собирает ровно то, что кладёт в адрес виджет Telegram. */
    private static Map<String, String> signed(Instant authDate) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("id", "4242");
        params.put("first_name", "Аня");
        params.put("username", "anya");
        params.put("auth_date", String.valueOf(authDate.getEpochSecond()));

        StringBuilder checkString = new StringBuilder();
        for (Map.Entry<String, String> field : new TreeMap<>(params).entrySet()) {
            if (!checkString.isEmpty()) {
                checkString.append('\n');
            }
            checkString.append(field.getKey()).append('=').append(field.getValue());
        }

        params.put("hash", hmac(checkString.toString()));
        return params;
    }

    private static String hmac(String data) {
        try {
            byte[] key = MessageDigest.getInstance("SHA-256")
                    .digest(TOKEN.getBytes(StandardCharsets.UTF_8));
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

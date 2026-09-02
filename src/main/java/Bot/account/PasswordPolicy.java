package Bot.account;

/**
 * Каким должен быть пароль, чтобы его приняли.
 *
 * <p>Ответственность одна: сказать «нет» и объяснить, чем именно плох
 * предложенный пароль. Раньше требование было одно — восемь знаков, — и
 * {@code 12345678} его удовлетворяло: это не «слабый пароль», это первый
 * вариант любого перебора. Списки утёкших паролей открыты, и подбор начинают
 * не с перебора всех комбинаций, а с них.</p>
 *
 * <p>Проверки по внешней службе (api.pwnedpasswords.com и её k-anonymity) здесь
 * нет сознательно: она означала бы обращение наружу на каждую регистрацию — с
 * домашней машины, синхронно, с зависимостью входа от чужой доступности. Взамен
 * рядом лежит {@code security/common-passwords.txt} — верхушка тех же списков,
 * включая русскую раскладку. Полный список утёкших она не заменяет и на это не
 * претендует.</p>
 *
 * <p>Ограничения сверху нет: длинная фраза лучше короткой мешанины, и обрезать
 * её незачем. BCrypt считает только первые 72 байта — этого хватает.</p>
 */
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

@Slf4j
public final class PasswordPolicy {

    /** Короче этого пароль не принимаем: подбор перебором становится реальным. */
    public static final int MIN_LENGTH = 8;

    /** Часть адреса до «собаки» короче этого в расчёт не берём: слишком общая. */
    private static final int MEANINGFUL_NAME_LENGTH = 4;

    private static final String RESOURCE = "/security/common-passwords.txt";

    private static final Set<String> COMMON = load();

    private PasswordPolicy() {
    }

    /**
     * Проверяет пароль перед тем, как его хешировать.
     *
     * @param password что предложил человек
     * @param email    его адрес — из пароля он не должен выводиться
     * @throws IllegalArgumentException с готовым текстом для страницы
     */
    public static void check(String password, String email) {
        if (password == null || password.length() < MIN_LENGTH) {
            throw new IllegalArgumentException(
                    "Пароль должен быть не короче " + MIN_LENGTH + " символов");
        }
        String lower = password.toLowerCase(Locale.ROOT);

        if (COMMON.contains(lower)) {
            throw new IllegalArgumentException(
                    "Этот пароль есть в списках, с которых начинают подбор. "
                            + "Придумайте другой — лучше всего длинную фразу.");
        }
        if (isOneCharacter(lower) || isSequence(lower)) {
            throw new IllegalArgumentException(
                    "Такой пароль подбирается с первой попытки: в нём нет ничего, "
                            + "кроме одного символа или подряд идущих знаков.");
        }
        if (repeatsTheName(lower, email)) {
            throw new IllegalArgumentException(
                    "Пароль повторяет вашу почту — его угадают первым делом.");
        }
    }

    /* ───────── helpers ───────── */

    /** «аааааааа» и «11111111»: длина есть, разнообразия нет. */
    private static boolean isOneCharacter(String password) {
        return password.chars().distinct().count() == 1;
    }

    /** «12345678», «abcdefgh» и они же задом наперёд. */
    private static boolean isSequence(String password) {
        int step = password.charAt(1) - password.charAt(0);
        if (step != 1 && step != -1) {
            return false;
        }
        for (int i = 2; i < password.length(); i++) {
            if (password.charAt(i) - password.charAt(i - 1) != step) {
                return false;
            }
        }
        return true;
    }

    /** Пароль, выводимый из адреса: имя целиком или оно же с приписками. */
    private static boolean repeatsTheName(String password, String email) {
        if (email == null) {
            return false;
        }
        int at = email.indexOf('@');
        String name = (at > 0 ? email.substring(0, at) : email)
                .toLowerCase(Locale.ROOT).strip();
        return name.length() >= MEANINGFUL_NAME_LENGTH && password.contains(name);
    }

    /**
     * Читает список один раз при загрузке класса.
     *
     * <p>Пропавший файл не должен ронять регистрацию: без списка остаются
     * длина и остальные правила — это хуже, но работает.</p>
     */
    private static Set<String> load() {
        Set<String> words = new HashSet<>();
        try (InputStream in = PasswordPolicy.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                log.warn("Не найден список частых паролей {} — проверка по нему выключена",
                        RESOURCE);
                return Set.of();
            }
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                String word = line.strip().toLowerCase(Locale.ROOT);
                if (!word.isEmpty() && !word.startsWith("#")) {
                    words.add(word);
                }
            }
        } catch (IOException e) {
            log.warn("Не удалось прочитать список частых паролей — проверка по нему выключена", e);
            return Set.of();
        }
        return Collections.unmodifiableSet(words);
    }
}

package Bot.account;

/**
 * Что считается годным паролем.
 *
 * <p>До этих правил требование было одно — восемь знаков, — и «12345678» его
 * выполняло. Подбор начинают не с перебора всех комбинаций, а со списков
 * вроде того, что лежит в {@code security/common-passwords.txt}.</p>
 */
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PasswordPolicyTest {

    private static final String EMAIL = "anya.petrova@example.com";

    @Test
    void shortPasswordIsRefused() {
        assertThatThrownBy(() -> PasswordPolicy.check("семь12", EMAIL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("8");
    }

    @Test
    void nullIsRefusedAndDoesNotBreakAnything() {
        assertThatThrownBy(() -> PasswordPolicy.check(null, EMAIL))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Верхушка списков подбора — в любом регистре. */
    @Test
    void wellKnownPasswordIsRefused() {
        assertThatThrownBy(() -> PasswordPolicy.check("password123", EMAIL))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PasswordPolicy.check("QwErTy123", EMAIL))
                .isInstanceOf(IllegalArgumentException.class);
        // Русская раскладка ничем не лучше: она в тех же списках
        assertThatThrownBy(() -> PasswordPolicy.check("фывапролд", EMAIL))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void oneCharacterOrPlainSequenceIsRefused() {
        assertThatThrownBy(() -> PasswordPolicy.check("аааааааааа", EMAIL))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PasswordPolicy.check("abcdefghij", EMAIL))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PasswordPolicy.check("9876543210", EMAIL))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Пароль, выводимый из адреса, угадывают первым делом. */
    @Test
    void passwordMadeOfTheEmailIsRefused() {
        assertThatThrownBy(() -> PasswordPolicy.check("anya.petrova", EMAIL))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PasswordPolicy.check("Anya.Petrova2026", EMAIL))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Короткое имя до «собаки» в расчёт не берём: совпадение будет случайным. */
    @Test
    void shortNameFromTheEmailIsNotHeldAgainstThePassword() {
        assertThatCode(() -> PasswordPolicy.check("ян-и-длинная-фраза", "ян@example.com"))
                .doesNotThrowAnyException();
    }

    @Test
    void longPhraseIsAccepted() {
        assertThatCode(() -> PasswordPolicy.check("бумажный кораблик у моста", EMAIL))
                .doesNotThrowAnyException();
        assertThatCode(() -> PasswordPolicy.check("Tr0ubled-Water-77", EMAIL))
                .doesNotThrowAnyException();
    }
}

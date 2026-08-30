package Bot.owner;

/**
 * Владелец задачи: чат в Telegram или аккаунт на сайте.
 *
 * <p>Ответственность: заменить сквозной {@code long chatId}, на котором держался
 * весь код. Пока пользователь один и приходит из Telegram, разницы не видно, но
 * у аккаунта на сайте чата нет вовсе — а результат ему всё равно нужно отдать.
 * Пара «тип + идентификатор» ложится в две колонки таблицы {@code jobs} и
 * одинаково работает для обоих случаев.</p>
 *
 * <p>Идентификатор — строка, а не число: у Telegram это chatId, у аккаунта
 * будет UUID. Общего числового пространства у них нет.</p>
 */
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.Objects;
import java.util.regex.Pattern;

public record Owner(OwnerType type, String id) {

    /** Chat id в Telegram — целое число; у групп и каналов оно отрицательное. */
    private static final Pattern TELEGRAM_ID = Pattern.compile("-?\\d{1,19}");

    /** UUID аккаунта ровно в каноническом виде: {@code UUID.fromString} слишком добр. */
    private static final Pattern ACCOUNT_ID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    /**
     * Форма идентификатора проверяется здесь, а не там, где о ней вспомнят.
     *
     * <p>Владелец приезжает по сети — в теле запроса к дому и в записи спула на
     * диске, — а из него {@link #storageKey()} собирает путь в хранилище. Пока
     * проверки не было, {@code id} вида {@code ../../..} уводил запись за
     * пределы каталога с файлами: сам по себе {@code /internal} закрыт ключом,
     * но VPS — единственная машина в интернете, и её взлом не должен
     * превращаться в запись файлов куда угодно на домашней.</p>
     */
    public Owner {
        Objects.requireNonNull(type, "type");
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id владельца не может быть пустым");
        }
        Pattern shape = type == OwnerType.TELEGRAM ? TELEGRAM_ID : ACCOUNT_ID;
        if (!shape.matcher(id).matches()) {
            // Сам id в сообщение не попадает: он пришёл снаружи, а сообщение
            // уходит в журнал и оттуда админу в чат
            throw new IllegalArgumentException("Недопустимый id владельца типа " + type);
        }
    }

    public static Owner telegram(long chatId) {
        return new Owner(OwnerType.TELEGRAM, String.valueOf(chatId));
    }

    public static Owner account(String accountId) {
        return new Owner(OwnerType.ACCOUNT, accountId);
    }

    /**
     * {@code @JsonIgnore} здесь не украшение: владелец ездит по сети — в теле
     * запроса к дому и в записи спула на диске. Jackson принимает {@code isXxx}
     * за свойство и пишет рядом с {@code type} производное {@code telegram},
     * которое потом некуда прочитать: у записи такого поля нет.
     */
    @JsonIgnore
    public boolean isTelegram() {
        return type == OwnerType.TELEGRAM;
    }

    /**
     * Ключ каталога в файловом хранилище.
     *
     * <p>У чата это по-прежнему chatId: каталоги со всем уже скачанным названы
     * числом, и смена правила осиротила бы их разом. У аккаунта числа нет, и
     * его каталог называется {@code acc-<uuid>} — с числовым именем такое имя
     * не совпадёт никогда.</p>
     */
    @JsonIgnore
    public String storageKey() {
        return isTelegram() ? id : "acc-" + id;
    }

    /**
     * Chat id для отправки в Telegram.
     *
     * <p>Бросает исключение для аккаунта сайта — и это правильнее, чем вернуть
     * что-нибудь: сообщение такому владельцу отправить некуда, и молча потерять
     * результат хуже, чем упасть на месте ошибки.</p>
     */
    public long telegramChatId() {
        if (!isTelegram()) {
            throw new IllegalStateException("У владельца " + this + " нет чата в Telegram");
        }
        return Long.parseLong(id);
    }

    @Override
    public String toString() {
        return type + ":" + id;
    }

    public enum OwnerType { TELEGRAM, ACCOUNT }
}

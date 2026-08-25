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
import java.util.Objects;

public record Owner(OwnerType type, String id) {

    public Owner {
        Objects.requireNonNull(type, "type");
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id владельца не может быть пустым");
        }
    }

    public static Owner telegram(long chatId) {
        return new Owner(OwnerType.TELEGRAM, String.valueOf(chatId));
    }

    public static Owner account(String accountId) {
        return new Owner(OwnerType.ACCOUNT, accountId);
    }

    public boolean isTelegram() {
        return type == OwnerType.TELEGRAM;
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

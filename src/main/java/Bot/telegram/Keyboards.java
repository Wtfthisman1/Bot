package Bot.telegram;

/**
 * Инлайн-клавиатуры бота и коды callback-кнопок.
 *
 * <p>Ответственность: единственное место, где описаны кнопки главного меню и
 * строки {@code callback_data}. Раньше кнопки собирались в
 * {@code ActionChoiceService} и в callback зашивался {@code url.hashCode()};
 * из-за этого кнопка «протухала» вместе с состоянием и переставала работать.
 * Теперь callback_data — фиксированные константы, а ссылка живёт в
 * {@code UserSessionService}.</p>
 *
 * <p>Лимит Telegram на callback_data — 64 байта; все константы ниже заведомо
 * короче, поэтому кнопка не может «сломаться» из-за длинной ссылки.</p>
 */
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.util.List;

public final class Keyboards {

    public static final String CB_TRANSCRIBE = "menu:transcribe";
    public static final String CB_DOWNLOAD   = "menu:download";
    public static final String CB_UPLOAD     = "menu:upload";
    public static final String CB_STATUS     = "menu:status";
    public static final String CB_HELP       = "menu:help";
    public static final String CB_CANCEL     = "menu:cancel";
    public static final String CB_DL_AUDIO   = "dl:audio";
    public static final String CB_DL_VIDEO   = "dl:video";

    private Keyboards() {
    }

    /** Главное меню: все действия бота доступны кнопками, без ввода команд. */
    public static InlineKeyboardMarkup mainMenu() {
        return keyboard(
                List.of(button("📝 Транскрибировать", CB_TRANSCRIBE),
                        button("📥 Скачать", CB_DOWNLOAD)),
                List.of(button("📤 Загрузить файлы", CB_UPLOAD),
                        button("📊 Статус", CB_STATUS)),
                List.of(button("❓ Помощь", CB_HELP))
        );
    }

    /** Выбор, что именно скачать, — спрашивается сразу после кнопки «Скачать». */
    public static InlineKeyboardMarkup downloadKind() {
        return keyboard(
                List.of(button("🎵 Аудио", CB_DL_AUDIO),
                        button("🎬 Видео", CB_DL_VIDEO)),
                List.of(button("↩️ Отмена", CB_CANCEL))
        );
    }

    /** Показывается, пока бот ждёт ссылку: единственный осмысленный выход — отмена. */
    public static InlineKeyboardMarkup cancel() {
        return keyboard(List.of(button("↩️ Отмена", CB_CANCEL)));
    }

    private static InlineKeyboardButton button(String text, String callbackData) {
        InlineKeyboardButton b = new InlineKeyboardButton();
        b.setText(text);
        b.setCallbackData(callbackData);
        return b;
    }

    @SafeVarargs
    private static InlineKeyboardMarkup keyboard(List<InlineKeyboardButton>... rows) {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(List.of(rows));
        return markup;
    }
}

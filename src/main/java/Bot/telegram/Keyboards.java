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
import Bot.transcription.TranscriptFormat;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.util.ArrayList;
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

    /**
     * Кнопки под готовой расшифровкой — единственные, чей callback несёт данные:
     * {@code tr:<код формата>:<id расшифровки>}. Идентификатор короткий
     * (12 символов), поэтому в 64 байта лимита укладывается с большим запасом.
     */
    public static final String CB_TRANSCRIPT_PREFIX = "tr:";

    /**
     * Кнопка «Выжимка» под готовой расшифровкой: {@code sum:<id расшифровки>}.
     *
     * <p>Отдельный префикс, а не ещё один формат: форматы отдают уже готовый
     * файл, а здесь заказывается счёт на видеокарте, и ответ придёт отдельным
     * сообщением через несколько минут.</p>
     */
    public static final String CB_SUMMARY_PREFIX = "sum:";

    /**
     * Подтверждение входа на сайт: {@code login:yes:<код>} и {@code login:no:<код>}.
     *
     * <p>Код — 22 символа, вместе с префиксом это 32 байта: лимит Telegram
     * в 64 байта на всю строку выдержан с запасом.</p>
     */
    public static final String CB_LOGIN_PREFIX = "login:";

    /** Подтверждение привязки чата к аккаунту: {@code link:yes:<код>} и {@code link:no:<код>}. */
    public static final String CB_LINK_PREFIX = "lnk:";

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

    /**
     * Что можно сделать с уже присланной расшифровкой: забрать в другом формате
     * или попросить выжимку.
     *
     * <p>Кнопками, а не четырьмя файлами подряд: субтитры и Word нужны не
     * каждому, а засыпать чат вложениями после каждой транскрипции — плохой
     * обмен ради редкого случая.</p>
     *
     * <p>Выжимка идёт отдельным рядом, ниже форматов: это единственная кнопка,
     * которая не отдаёт готовое, а занимает видеокарту на минуты.</p>
     *
     * @param summary показывать ли выжимку — модели может не быть вовсе
     */
    public static InlineKeyboardMarkup underTranscript(String id, List<TranscriptFormat> formats,
                                                       boolean summary) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        // По две кнопки в ряд: подписи длинные, в один ряд Telegram их сожмёт
        for (int i = 0; i < formats.size(); i += 2) {
            List<InlineKeyboardButton> row = new ArrayList<>(2);
            for (int j = i; j < Math.min(i + 2, formats.size()); j++) {
                TranscriptFormat format = formats.get(j);
                row.add(button(format.buttonLabel(), transcriptCallback(format, id)));
            }
            rows.add(row);
        }
        if (summary) {
            rows.add(List.of(button("✨ Выжимка", CB_SUMMARY_PREFIX + id)));
        }

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(rows);
        return markup;
    }

    /** Строка {@code callback_data} для кнопки формата — собирается только здесь. */
    public static String transcriptCallback(TranscriptFormat format, String id) {
        return CB_TRANSCRIPT_PREFIX + format.code() + ':' + id;
    }

    /**
     * Подтвердить или отклонить вход на сайт.
     *
     * <p>Две кнопки, а не одна: человек мог открыть чужую ссылку, и «Это не я»
     * должно быть таким же простым действием, как согласие.</p>
     */
    public static InlineKeyboardMarkup loginConfirm(String code) {
        return keyboard(
                List.of(button("✅ Это я, войти", CB_LOGIN_PREFIX + "yes:" + code)),
                List.of(button("❌ Это не я", CB_LOGIN_PREFIX + "no:" + code))
        );
    }

    /**
     * Привязать этот чат к аккаунту на сайте или отказаться.
     *
     * <p>Как и у входа, две кнопки: ссылку с кодом можно прислать чужому
     * человеку, и тогда его переписка досталась бы отправителю.</p>
     */
    public static InlineKeyboardMarkup linkConfirm(String code) {
        return keyboard(
                List.of(button("✅ Да, это мой аккаунт", CB_LINK_PREFIX + "yes:" + code)),
                List.of(button("❌ Нет", CB_LINK_PREFIX + "no:" + code))
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

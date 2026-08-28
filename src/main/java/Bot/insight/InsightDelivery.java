package Bot.insight;

/**
 * Ответ модели, отправленный в чат.
 *
 * <p>Ответственность: превратить посчитанное в сообщение Telegram. Нужна
 * потому, что заказ из чата отвечать некуда: страницы, которая сама покажет
 * результат, там нет, а модель считает минутами — к её концу человек уже
 * листает другое.</p>
 *
 * <p>Отправляет дом, а не бот на VPS: считает дом, и городить ради одного
 * сообщения обратный вызов на VPS незачем — токен бота есть у обеих
 * половин. Тем же путём уходит и готовая расшифровка
 * ({@link Bot.notify.TelegramJobNotifier}).</p>
 *
 * <p>Разметка не включается намеренно: в пересказе живой речи попадаются и
 * угловые скобки, и звёздочки, и подчёркивания. С {@code parse_mode} Telegram
 * на таком тексте отвечает ошибкой, и человек не получает ничего.</p>
 */
import Bot.config.Profiles;
import Bot.telegram.Keyboards;
import Bot.telegram.MessageSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Profile(Profiles.HOME)
@Component
@RequiredArgsConstructor
@Slf4j
public class InsightDelivery {

    private final MessageSender messageSender;

    /**
     * Отправляет готовое: заголовок и сам текст одним сообщением.
     *
     * <p>Длину не проверяем — {@link MessageSender} режет текст на части сам.
     * Выжимка часовой записи в одно сообщение Telegram не влезает.</p>
     */
    public void ready(long chatId, InsightKind kind, String text) {
        log.info("Обработка уходит в чат: chatId={}, вид={}, символов={}",
                chatId, kind, text.length());
        // Заголовком идёт название вида, а не «выжимка готова»: у видов разный
        // род, и одна фраза на оба звучала бы неряшливо
        messageSender.sendMessage(chatId, "✨ %s\n\n%s".formatted(kind.title(), text));
    }

    /**
     * Объясняет, что не вышло.
     *
     * <p>С меню: после неудачи первое желание — попробовать снова, и кнопка
     * должна быть под рукой. Так же отвечает и сорвавшаяся расшифровка.</p>
     */
    public void failed(long chatId, InsightKind kind, String error) {
        messageSender.sendMessageWithKeyboard(chatId,
                "❌ %s: не вышло. %s".formatted(kind.title(), error),
                null, Keyboards.mainMenu());
    }
}

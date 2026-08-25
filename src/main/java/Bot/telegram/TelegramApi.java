package Bot.telegram;

/**
 * Отправка запросов в Telegram Bot API — без приёма апдейтов.
 *
 * <p>Ответственность: единственное место, откуда уходит {@code execute}.
 * Раньше эту роль играл {@link TelegramBot}, но он вдобавок держит long
 * polling, а получать апдейты может только один процесс. После разделения
 * отвечать пользователю нужно с обеих сторон: VPS — на нажатия кнопок, дом —
 * готовыми расшифровками и ссылками на файлы. Отправка при этом ни на чём не
 * завязана: это обычные HTTPS-запросы с тем же токеном.</p>
 *
 * <p>Заодно исчезает {@code applicationContext.getBean(TelegramBot.class)}:
 * обход циклической зависимости был нужен ровно потому, что отправитель и
 * получатель были одним бином.</p>
 */
import Bot.config.BotConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.DefaultAbsSender;
import org.telegram.telegrambots.bots.DefaultBotOptions;

@Component
@Slf4j
public class TelegramApi extends DefaultAbsSender {

    public TelegramApi(BotConfig config) {
        super(new DefaultBotOptions(), config.getBotToken());
    }
}

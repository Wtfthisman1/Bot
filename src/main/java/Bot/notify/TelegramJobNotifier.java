package Bot.notify;

/**
 * Доставка результата в чат Telegram.
 *
 * <p>Ответственность: то, что раньше делал воркер напрямую, — отправить
 * расшифровку с кнопками форматов или объяснить, почему не вышло. Связан с
 * {@link TranscriptDeliveryService} и {@link MessageSender}.</p>
 */
import Bot.owner.Owner;
import Bot.telegram.Keyboards;
import Bot.telegram.MessageSender;
import Bot.transcription.TranscriptDeliveryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Component
@RequiredArgsConstructor
public class TelegramJobNotifier implements JobNotifier {

    private final TranscriptDeliveryService transcriptDelivery;
    private final MessageSender messageSender;

    @Override
    public boolean supports(Owner owner) {
        return owner.isTelegram();
    }

    @Override
    public void transcriptReady(Owner owner, Path txt) {
        transcriptDelivery.deliver(owner.telegramChatId(), txt);
    }

    @Override
    public void failed(Owner owner, String message) {
        // С меню, а не просто текстом: после неудачи первое желание —
        // попробовать снова, и кнопка должна быть под рукой
        messageSender.sendMessageWithKeyboard(owner.telegramChatId(), message, null, Keyboards.mainMenu());
    }
}

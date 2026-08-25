package Bot.handler;

/**
 * Обработка нажатий на инлайн-кнопки.
 *
 * <p>Ответственность: перевести {@code callback_data} в действие. Кнопки
 * работают в обе стороны: если ссылка уже прислана и ждёт выбора — работа
 * стартует сразу, иначе бот просит ссылку.</p>
 *
 * <p>«Скачать» — двухшаговая: сначала спрашивается формат (аудио/видео), и лишь
 * потом запрашивается ссылка либо запускается работа по уже отложенной. Первый
 * шаг состояние не трогает, поэтому отложенная ссылка доживает до выбора
 * формата и подхватывается вторым нажатием.</p>
 */
import Bot.handler.UserSessionService.Mode;
import Bot.handler.UserSessionService.Pending;
import Bot.processing.MediaKind;
import Bot.telegram.Keyboards;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class CallbackHandler {

    private final UserSessionService sessionService;
    private final UrlActionService urlActionService;
    private final CommandHandler commandHandler;

    public void handle(long chatId, String callbackData, String userName) {
        if (callbackData == null || callbackData.isBlank()) {
            log.warn("Пустой callback: chatId={}", chatId);
            return;
        }

        switch (callbackData) {
            case Keyboards.CB_TRANSCRIBE ->
                    startOrAsk(chatId, new Pending(Mode.TRANSCRIBE, MediaKind.AUDIO), userName);
            case Keyboards.CB_DOWNLOAD -> {
                log.info("Выбрано скачивание, спрашиваю формат: chatId={}", chatId);
                commandHandler.askDownloadKind(chatId);
            }
            case Keyboards.CB_DL_AUDIO ->
                    startOrAsk(chatId, new Pending(Mode.DOWNLOAD, MediaKind.AUDIO), userName);
            case Keyboards.CB_DL_VIDEO ->
                    startOrAsk(chatId, new Pending(Mode.DOWNLOAD, MediaKind.VIDEO), userName);
            case Keyboards.CB_UPLOAD -> {
                sessionService.clear(chatId);
                commandHandler.upload(chatId);
            }
            case Keyboards.CB_STATUS -> commandHandler.status(chatId);
            case Keyboards.CB_HELP -> commandHandler.help(chatId);
            case Keyboards.CB_CANCEL -> commandHandler.cancel(chatId);
            default -> {
                log.warn("Неизвестный callback: chatId={}, data='{}'", chatId, callbackData);
                commandHandler.showMenu(chatId, "🤔 Эта кнопка устарела. Выберите действие:");
            }
        }
    }

    /**
     * Ссылка уже лежит в состоянии — стартуем сразу; иначе просим прислать её.
     */
    private void startOrAsk(long chatId, Pending pending, String userName) {
        Optional<String> pendingUrl = sessionService.takePendingUrl(chatId);
        if (pendingUrl.isPresent()) {
            log.info("Действие выбрано для отложенной ссылки: chatId={}, mode={}, media={}",
                    chatId, pending.mode(), pending.media());
            urlActionService.start(chatId, pending, pendingUrl.get(), userName);
            return;
        }

        log.info("Действие выбрано, ждём ссылку: chatId={}, mode={}, media={}",
                chatId, pending.mode(), pending.media());
        commandHandler.askForLink(chatId, pending.mode(), pending.media());
    }
}

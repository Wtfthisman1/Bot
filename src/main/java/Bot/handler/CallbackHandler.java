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
import Bot.home.HomeApi;
import Bot.owner.Owner;
import Bot.processing.MediaKind;
import Bot.telegram.Keyboards;
import Bot.transcription.TranscriptFormat;
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
    private final HomeApi home;

    public void handle(long chatId, String callbackData, String userName) {
        if (callbackData == null || callbackData.isBlank()) {
            log.warn("Пустой callback: chatId={}", chatId);
            return;
        }

        // Единственные кнопки с данными в callback — форматы расшифровки:
        // их код не постоянная, поэтому switch по константам их не поймает
        if (callbackData.startsWith(Keyboards.CB_TRANSCRIPT_PREFIX)) {
            handleTranscriptFormat(chatId, callbackData);
            return;
        }

        if (callbackData.startsWith(Keyboards.CB_LOGIN_PREFIX)) {
            handleLoginConfirmation(chatId, callbackData, userName);
            return;
        }

        if (callbackData.startsWith(Keyboards.CB_LINK_PREFIX)) {
            handleLinkConfirmation(chatId, callbackData);
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
     * Разбирает {@code login:yes|no:<код>} — ответ на просьбу подтвердить вход.
     *
     * <p>Отказ ничего не делает намеренно: код просто остаётся неподтверждённым
     * и через несколько минут протухнет сам. Гасить его по «Это не я» значило бы
     * дать постороннему способ мешать чужому входу.</p>
     */
    private void handleLoginConfirmation(long chatId, String callbackData, String userName) {
        String[] parts = callbackData.split(":", 3);
        if (parts.length < 3) {
            log.warn("Неразбираемое подтверждение входа: chatId={}", chatId);
            commandHandler.showMenu(chatId, "🤔 Эта кнопка устарела. Выберите действие:");
            return;
        }

        if (!"yes".equals(parts[1])) {
            log.info("Вход на сайт отклонён из чата: chatId={}", chatId);
            commandHandler.showMenu(chatId,
                    "👌 Понял, вход не подтверждаю. Если ссылку прислал кто-то другой — "
                            + "просто не открывайте её.");
            return;
        }

        try {
            if (home.confirmBotLogin(chatId, parts[2], userName)) {
                commandHandler.showMenu(chatId,
                        "✅ Вход подтверждён. Возвращайтесь на вкладку с сайтом — "
                                + "она откроет кабинет сама.");
            } else {
                commandHandler.showMenu(chatId,
                        "🕓 Код устарел или уже сработал. Откройте страницу входа заново.");
            }
        } catch (Exception e) {
            log.error("Не удалось подтвердить вход на сайт: chatId={}", chatId, e);
            commandHandler.showMenu(chatId,
                    "🌙 Сейчас не выходит подтвердить вход — рабочая машина недоступна.");
        }
    }

    /**
     * Разбирает {@code lnk:yes|no:<код>} — ответ на просьбу привязать чат.
     *
     * <p>Отказ, как и у входа, ничего не гасит: код протухнет сам. Гасить его
     * по «Нет» значило бы дать постороннему способ мешать чужой привязке.</p>
     */
    private void handleLinkConfirmation(long chatId, String callbackData) {
        String[] parts = callbackData.split(":", 3);
        if (parts.length < 3) {
            log.warn("Неразбираемое подтверждение привязки: chatId={}", chatId);
            commandHandler.showMenu(chatId, "🤔 Эта кнопка устарела. Выберите действие:");
            return;
        }

        if (!"yes".equals(parts[1])) {
            log.info("Привязка чата отклонена: chatId={}", chatId);
            commandHandler.showMenu(chatId, "👌 Понял, ничего не связываю.");
            return;
        }

        commandHandler.redeemLink(chatId, parts[2]);
    }

    /**
     * Разбирает {@code tr:<формат>:<id>} и отдаёт расшифровку в этом формате.
     *
     * <p>Битую строку не считаем ошибкой пользователя: он мог нажать кнопку из
     * очень старого сообщения. Показываем меню, как и на любой другой
     * устаревший callback.</p>
     */
    private void handleTranscriptFormat(long chatId, String callbackData) {
        String[] parts = callbackData.split(":", 3);
        Optional<TranscriptFormat> format = parts.length == 3
                ? TranscriptFormat.fromCode(parts[1])
                : Optional.empty();

        if (format.isEmpty() || parts[2].isBlank()) {
            log.warn("Не разобрал callback формата расшифровки: chatId={}, data='{}'", chatId, callbackData);
            commandHandler.showMenu(chatId, "🤔 Эта кнопка устарела. Выберите действие:");
            return;
        }

        log.info("Запрошен формат расшифровки: chatId={}, формат={}", chatId, format.get());
        home.sendTranscript(Owner.telegram(chatId), parts[2], format.get());
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

package Bot.handler;

/**
 * Обработка нажатий на инлайн-кнопки.
 *
 * <p>Ответственность: перевести {@code callback_data} в действие. Кнопки
 * работают в обе стороны: если ссылка уже прислана и ждёт выбора — работа
 * стартует сразу, иначе бот просит ссылку.</p>
 *
 * <p>Кнопок подтверждения — «Это я» под входом и «Да, это мой аккаунт» под
 * привязкой — здесь больше нет: обе подтверждали действие, начатое кем-то
 * другим. Вход теперь начинает сам чат ({@link CommandHandler#login}), а код
 * привязки человек набирает руками, глядя в свой кабинет.</p>
 *
 * <p>«Скачать» — двухшаговая: сначала спрашивается формат (аудио/видео), и лишь
 * потом запрашивается ссылка либо запускается работа по уже отложенной. Первый
 * шаг состояние не трогает, поэтому отложенная ссылка доживает до выбора
 * формата и подхватывается вторым нажатием.</p>
 */
import Bot.handler.UserSessionService.Mode;
import Bot.handler.UserSessionService.Pending;
import Bot.home.HomeApi;
import Bot.insight.InsightKind;
import Bot.owner.Owner;
import Bot.processing.MediaKind;
import Bot.telegram.Keyboards;
import Bot.transcription.TranscriptFormat;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.function.Consumer;

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

        if (callbackData.startsWith(Keyboards.CB_SUMMARY_PREFIX)) {
            withJob(chatId, callbackData, Keyboards.CB_SUMMARY_PREFIX,
                    jobId -> commandHandler.orderInsight(chatId, jobId, InsightKind.SUMMARY, null));
            return;
        }

        if (callbackData.startsWith(Keyboards.CB_TOPIC_PREFIX)) {
            withJob(chatId, callbackData, Keyboards.CB_TOPIC_PREFIX,
                    jobId -> commandHandler.askTopic(chatId, jobId));
            return;
        }

        if (callbackData.startsWith(Keyboards.CB_CANCEL_JOB_PREFIX)) {
            withJob(chatId, callbackData, Keyboards.CB_CANCEL_JOB_PREFIX,
                    jobId -> commandHandler.cancelJob(chatId, jobId));
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
            case Keyboards.CB_LOGIN -> commandHandler.login(chatId, userName);
            case Keyboards.CB_HELP -> commandHandler.help(chatId);
            case Keyboards.CB_CANCEL -> commandHandler.cancel(chatId);
            default -> {
                log.warn("Неизвестный callback: chatId={}, data='{}'", chatId, callbackData);
                commandHandler.showMenu(chatId, "🤔 Эта кнопка устарела. Выберите действие:");
            }
        }
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
     * Достаёт из кнопки номер задачи и передаёт его действию.
     *
     * <p>Кнопки обработки и остановки несут в callback один и тот же хвост — id
     * задачи, — и все одинаково устаревают: сообщение могло быть прислано год
     * назад. Пустой хвост означает именно это, и ответ на него общий.</p>
     */
    private void withJob(long chatId, String callbackData, String prefix,
                         Consumer<String> action) {
        String jobId = callbackData.substring(prefix.length());
        if (jobId.isBlank()) {
            log.warn("Кнопка обработки без задачи: chatId={}, data='{}'", chatId, callbackData);
            commandHandler.showMenu(chatId, "🤔 Эта кнопка устарела. Выберите действие:");
            return;
        }
        action.accept(jobId);
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

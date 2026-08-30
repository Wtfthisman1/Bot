package Bot.handler;

/**
 * Обработка входящих сообщений и медиа от пользователей.
 *
 * <p>Ответственность: разобрать текст (ссылки/подсказка), скачать медиа из
 * Telegram и запустить транскрипцию. Выбор действия по ссылке делегируется
 * {@link UserSessionService} + {@link UrlActionService}: здесь нет ни текстовых
 * «транскрибировать/скачать», ни разбора callback — только маршрутизация.</p>
 *
 * <p>Связан с {@link HomeApi} (туда уходит вся работа), {@link MessageSender}
 * и {@link UserSessionService}. Основные методы:
 * {@code handleText}, {@code handleVoice}, {@code handleAudio},
 * {@code handleVideo}, {@code handleDocument}.</p>
 */
import Bot.handler.UserSessionService.Pending;
import Bot.home.HomeApi;
import Bot.home.HomeApi.Acceptance;
import Bot.home.HomeApi.TelegramFile;
import Bot.home.HomeUnavailableException;
import Bot.insight.InsightKind;
import Bot.telegram.FileTooLargeException;
import Bot.telegram.Keyboards;
import Bot.telegram.MessageSender;
import Bot.telegram.TelegramFileDownloader;
import Bot.owner.Owner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.objects.Audio;
import org.telegram.telegrambots.meta.api.objects.Document;
import org.telegram.telegrambots.meta.api.objects.Video;
import org.telegram.telegrambots.meta.api.objects.Voice;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class MessageHandler {

    private static final Pattern URL_PATTERN = Pattern.compile(
            "https?://[\\w\\d\\-._~:/?#\\[\\]@!$&'()*+,;=%]+");

    private static final List<String> VIDEO_EXTENSIONS =
            List.of(".mp4", ".avi", ".mkv", ".mov", ".webm");

    /**
     * Пул Spring Boot. Имя поля обязано совпадать с именем бина: в Boot 3.5
     * {@code taskScheduler} тоже стал {@link TaskExecutor}, и выбор по типу
     * перестал быть однозначным — см. {@code MessageSender}.
     */
    private final TaskExecutor applicationTaskExecutor;
    private final HomeApi home;
    private final MessageSender messageSender;
    private final UserSessionService sessionService;
    private final UrlActionService urlActionService;
    private final CommandHandler commandHandler;

    /* ───────────────── текст ───────────────── */

    /**
     * Разбирает текст: ссылка запускает работу, всё остальное возвращает в меню.
     *
     * <p>Ссылка проверяется раньше состояния ожидания — иначе присланная не в
     * тот момент ссылка трактовалась бы как «непонятный ответ».</p>
     *
     * <p>Исключение — тема разбора: её бот только что попросил сам, и весь
     * текст ответа целиком и есть тема. Ссылку внутри неё искать нельзя, иначе
     * вопрос «что говорили про youtube.com» превратился бы в расшифровку.</p>
     */
    public void handleText(long chatId, String text, String name) {
        Optional<String> topicJob = sessionService.takeTopicJob(chatId);
        if (topicJob.isPresent()) {
            log.info("Получена тема разбора: chatId={}, длина={}", chatId, text.length());
            commandHandler.orderInsight(chatId, topicJob.get(), InsightKind.TOPIC, text);
            return;
        }

        List<String> urls = extractUrls(text);

        if (urls.isEmpty()) {
            if (sessionService.isAwaitingLink(chatId)) {
                log.info("Вместо ссылки пришёл текст: chatId={}", chatId);
                messageSender.sendMessageWithKeyboard(chatId,
                        "🔗 Это не похоже на ссылку. Пришлите адрес, начинающийся с http:// или https://",
                        null, Keyboards.cancel());
            } else {
                commandHandler.showMenu(chatId,
                        "💡 Пришлите голосовое, аудио, видео или ссылку — либо выберите действие:");
            }
            return;
        }

        log.info("Получено ссылок: chatId={}, количество={}", chatId, urls.size());

        Optional<Pending> awaiting = sessionService.takeAwaiting(chatId);
        if (awaiting.isPresent()) {
            urlActionService.start(chatId, awaiting.get(), urls, name);
            return;
        }

        // Действие не выбрано: держим ссылку и показываем кнопки.
        // Несколько ссылок без выбора — берём первую, чтобы кнопка была однозначной.
        sessionService.rememberUrl(chatId, urls.get(0));
        messageSender.sendMessageWithKeyboard(chatId,
                "🔗 Ссылка получена. Что с ней сделать?",
                null, Keyboards.mainMenu());
    }

    /* ───────────────── медиа ───────────────── */

    public void handleVoice(long chatId, Voice voice, String name) {
        log.info("Получено голосовое: chatId={}, длительность={}с, размер={}",
                chatId, voice.getDuration(), voice.getFileSize());
        transcribeMedia(chatId, voice.getFileSize(), "🎤 Голосовое получено. Расшифровываю...",
                new TelegramFile(voice.getFileId(), "voice.ogg", TelegramFile.Kind.VOICE));
    }

    public void handleAudio(long chatId, Audio audio, String name) {
        log.info("Получено аудио: chatId={}, файл='{}', размер={}",
                chatId, audio.getFileName(), audio.getFileSize());
        String fileName = audio.getFileName() != null ? audio.getFileName() : "audio.mp3";
        transcribeMedia(chatId, audio.getFileSize(), "🎵 Аудио получено. Расшифровываю...",
                new TelegramFile(audio.getFileId(), fileName, TelegramFile.Kind.AUDIO));
    }

    public void handleVideo(long chatId, Video video, String name) {
        log.info("Получено видео: chatId={}, файл='{}', размер={}",
                chatId, video.getFileName(), video.getFileSize());
        String fileName = video.getFileName() != null ? video.getFileName() : "video.mp4";
        transcribeMedia(chatId, video.getFileSize(), "🎬 Видео получено. Расшифровываю...",
                new TelegramFile(video.getFileId(), fileName, TelegramFile.Kind.VIDEO));
    }

    public void handleDocument(long chatId, Document document, String name) {
        String fileName = document.getFileName();
        log.info("Получен документ: chatId={}, файл='{}', размер={}",
                chatId, fileName, document.getFileSize());

        if (!isMediaFile(fileName)) {
            log.info("Документ отклонён как неподдерживаемый: chatId={}, файл='{}'", chatId, fileName);
            commandHandler.showMenu(chatId,
                    "❌ Такой файл я расшифровать не смогу. Пришлите аудио или видео.");
            return;
        }

        transcribeMedia(chatId, document.getFileSize(), "📄 Файл получен. Расшифровываю...",
                new TelegramFile(document.getFileId(), fileName, TelegramFile.Kind.DOCUMENT));
    }

    /* ───────────────── общая механика ───────────────── */

    /**
     * Общий путь для всех медиа: проверка лимита → подтверждение → передача
     * файла домой.
     *
     * <p>Раньше эти шаги были скопированы в каждом {@code handleXxx};
     * различались только тексты и способ скачивания — они и остались параметрами.</p>
     *
     * <p>Домой уходит {@code fileId}, а не содержимое: файл забирает у Bot API
     * та машина, где он и будет считаться. Иначе запись прошла бы лишний круг
     * через VPS, который её всё равно не хранит.</p>
     *
     * <p>Фоновый поток нужен потому, что дом успевает и скачать файл, и
     * положить его в очередь, — на это уходят секунды, а ответ пользователю
     * должен уйти сразу.</p>
     */
    private void transcribeMedia(long chatId, Long fileSize, String ack, TelegramFile file) {
        if (tooLargeForBot(chatId, fileSize)) {
            return;
        }

        sessionService.clear(chatId);
        messageSender.sendChatAction(chatId, "typing");
        messageSender.sendMessage(chatId, ack);

        applicationTaskExecutor.execute(() -> {
            try {
                Acceptance acceptance = home.transcribeTelegramFile(Owner.telegram(chatId), file);
                if (acceptance.isDeferred()) {
                    // Подтверждение уже ушло — поправляем его, а не молчим
                    messageSender.sendMessage(chatId, acceptance.userMessage());
                }
            } catch (FileTooLargeException e) {
                log.info("Файл превысил лимит Bot API: chatId={}", chatId);
                sendUploadFormOffer(chatId, fileSize);
            } catch (HomeUnavailableException e) {
                // Фоновая задача до общего catch в TelegramBot не доходит
                log.warn("Домашняя машина не отвечает: chatId={}", chatId);
                messageSender.sendMessageWithKeyboard(chatId, HomeUnavailableException.USER_MESSAGE,
                        null, Keyboards.mainMenu());
            } catch (Exception e) {
                log.error("Ошибка обработки медиа: chatId={}", chatId, e);
                messageSender.sendMessageWithKeyboard(chatId, transcribeErrorMessage(e),
                        null, Keyboards.mainMenu());
            }
        });
    }

    /**
     * Отсекает файлы, которые бот физически не может забрать из чата.
     *
     * <p>Размер известен заранее из апдейта, поэтому предупреждение уходит до
     * скачивания. Если Telegram размер не прислал, лимит вскроется уже при
     * {@code getFile} — там срабатывает {@link FileTooLargeException}.</p>
     *
     * @return {@code true}, если файл слишком большой и обработку надо прекратить
     */
    private boolean tooLargeForBot(long chatId, Long fileSize) {
        if (fileSize == null || fileSize <= TelegramFileDownloader.TELEGRAM_FILE_LIMIT_BYTES) {
            return false;
        }
        log.info("Файл больше лимита Bot API: chatId={}, размер={} байт, лимит={} байт",
                chatId, fileSize, TelegramFileDownloader.TELEGRAM_FILE_LIMIT_BYTES);
        sendUploadFormOffer(chatId, fileSize);
        return true;
    }

    /** Объясняет лимит и выдаёт одноразовую ссылку на форму загрузки. */
    private void sendUploadFormOffer(long chatId, Long fileSize) {
        String sizeLine = fileSize != null
                ? "📏 Размер файла: %.1f МБ\n".formatted(fileSize / (1024.0 * 1024.0))
                : "";

        String message = """
                📦 <b>Файл слишком большой для чата</b>

                %sTelegram не отдаёт ботам файлы больше 20 МБ.

                <a href="%s">Открыть форму загрузки</a>

                • До 5 файлов, до 2500 МБ каждый
                • Ссылка одноразовая, действует 1 час
                • Расшифровка придёт сюда, в чат
                """.formatted(sizeLine,
                MessageSender.escapeHtml(home.uploadFormLink(Owner.telegram(chatId))));

        messageSender.sendMessage(chatId, message.strip(), "HTML");
    }

    /** Извлекает URL из текста. */
    private List<String> extractUrls(String text) {
        List<String> urls = new ArrayList<>();
        Matcher matcher = URL_PATTERN.matcher(text);
        while (matcher.find()) {
            urls.add(matcher.group());
        }
        return urls;
    }

    /** Документ имеет смысл расшифровывать, только если это аудио или видео. */
    private boolean isMediaFile(String fileName) {
        if (fileName == null) {
            return false;
        }
        String lower = fileName.toLowerCase();
        return VIDEO_EXTENSIONS.stream().anyMatch(lower::endsWith)
                || lower.endsWith(".mp3") || lower.endsWith(".wav")
                || lower.endsWith(".m4a") || lower.endsWith(".ogg")
                || lower.endsWith(".flac");
    }

    /** Короткое объяснение сбоя вместо стектрейса. */
    private String transcribeErrorMessage(Exception e) {
        String error = e.getMessage() != null ? e.getMessage().toLowerCase() : "";

        if (error.contains("не смог распознать речь") || error.contains("empty transcription")) {
            return "🎤 Не удалось распознать речь. Запись слишком короткая, тихая или без речи.";
        }
        if (error.contains("timeout")) {
            return "⏰ Обработка заняла слишком много времени. Попробуйте файл покороче.";
        }
        if (error.contains("model") || error.contains("whisper")) {
            return "🤖 Сбой системы распознавания. Попробуйте позже.";
        }
        return "❌ Не удалось обработать файл. Проверьте формат и попробуйте снова.";
    }
}

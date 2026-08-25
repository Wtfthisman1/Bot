package Bot.handler;

/**
 * Экраны бота: приветствие, запрос ссылки, справка, форма загрузки, статус.
 *
 * <p>Ответственность: собрать текст экрана и отправить его вместе с нужной
 * клавиатурой. Каждый экран — публичный метод, поэтому и текстовая команда
 * ({@code /start}), и нажатие кнопки ({@link CallbackHandler}) приводят к
 * одному и тому же результату: тексты не расходятся между двумя путями.</p>
 *
 * <p>Связан с {@link UploadService}, {@link StatusService}, {@link MessageSender},
 * {@link UserSessionService}.</p>
 */
import Bot.download.DownloadService;
import Bot.handler.UserSessionService.Mode;
import Bot.processing.MediaKind;
import Bot.service.StatusService;
import Bot.telegram.Keyboards;
import Bot.telegram.MessageSender;
import Bot.upload.UploadService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class CommandHandler {

    private final UploadService uploadService;
    private final MessageSender messageSender;
    private final StatusService statusService;
    private final UserSessionService sessionService;

    /** Маршрутизация текстовых команд на те же экраны, что и кнопки. */
    public void handleCommand(long chatId, String text, String name) {
        // «/start@MyBot» и «/start payload» — тоже /start
        String command = text.split("[\\s@]", 2)[0].toLowerCase();
        log.info("Команда: chatId={}, команда='{}'", chatId, command);

        switch (command) {
            case "/start" -> start(chatId, name);
            case "/help" -> help(chatId);
            case "/transcribe" -> askForLink(chatId, Mode.TRANSCRIBE, MediaKind.AUDIO);
            case "/download" -> askDownloadKind(chatId);
            case "/upload" -> upload(chatId);
            case "/status" -> status(chatId);
            case "/cancel" -> cancel(chatId);
            default -> {
                log.info("Неизвестная команда: chatId={}, команда='{}'", chatId, command);
                showMenu(chatId, "🤔 Не знаю такой команды. Выберите действие:");
            }
        }
    }

    /**
     * Приветствие: максимально коротко о том, что умеет бот, и сразу кнопки.
     * Длинные простыни переехали в {@link #help(long)} — их читают по запросу.
     */
    public void start(long chatId, String name) {
        sessionService.clear(chatId);
        String text = "👋 Привет%s! Я расшифровываю аудио и видео в текст и скачиваю видео по ссылке."
                .formatted(name != null && !name.isBlank() ? ", " + name : "");
        showMenu(chatId, text + "\n\nВыберите действие:");
    }

    /** Просит ссылку под уже выбранное действие. */
    public void askForLink(long chatId, Mode mode, MediaKind media) {
        sessionService.awaitLink(chatId, mode, media);
        String what = mode == Mode.TRANSCRIBE
                ? "расшифровать"
                : "скачать (" + (media == MediaKind.AUDIO ? "аудио" : "видео") + ")";
        messageSender.sendMessageWithKeyboard(chatId,
                "🔗 Пришлите ссылку на видео или аудио, которое нужно " + what + ".",
                null, Keyboards.cancel());
    }

    /** Спрашивает формат сразу после «Скачать», до запроса ссылки. */
    public void askDownloadKind(long chatId) {
        messageSender.sendMessageWithKeyboard(chatId,
                "📥 Что скачать?",
                null, Keyboards.downloadKind());
    }

    /** Отмена ожидания ссылки — возврат в меню. */
    public void cancel(long chatId) {
        sessionService.clear(chatId);
        showMenu(chatId, "↩️ Отменено. Выберите действие:");
    }

    /** Главное меню с произвольной подписью. */
    public void showMenu(long chatId, String text) {
        messageSender.sendMessageWithKeyboard(chatId, text, null, Keyboards.mainMenu());
    }

    public void help(long chatId) {
        String help = """
                📚 <b>Что умеет бот</b>

                📝 <b>Транскрибировать</b> — расшифровка в текст.
                Присылайте голосовое, аудио, видео или ссылку.

                📥 <b>Скачать</b> — аудио или видео по ссылке, формат выбираете кнопкой.
                В ответ придёт ссылка на файл, действительна 24 часа.

                📤 <b>Загрузить файлы</b> — форма для файлов больше 20 МБ.
                До 5 файлов, до 2500 МБ каждый.

                📦 <b>Ограничения:</b>
                • До 20 МБ — можно прямо в чат
                • Больше 20 МБ — Telegram не отдаёт файл боту, нужна форма загрузки

                🔗 <b>Ссылки:</b> YouTube, Vimeo, TikTok, Instagram, Twitter/X, Facebook

                ⏱️ Обработка идёт в фоне: голосовое — 1–3 мин, видео — от 5 мин.
                """;
        messageSender.sendMessageWithKeyboard(chatId, help.strip(), "HTML", Keyboards.mainMenu());
    }

    /** Выдаёт одноразовую ссылку на форму загрузки. */
    public void upload(long chatId) {
        messageSender.sendChatAction(chatId, "typing");

        String link = uploadService.generate(chatId);
        log.info("Выдана ссылка на форму загрузки: chatId={}", chatId);

        String html = """
                📤 <b>Форма загрузки</b>

                До 5 файлов или ссылок, до 2500 МБ каждый.
                Ссылка одноразовая и действует 1 час.

                <a href="%s">Открыть форму загрузки</a>
                """.formatted(MessageSender.escapeHtml(link));

        messageSender.sendMessage(chatId, html.strip(), "HTML");
    }

    /** Короткая сводка по задачам пользователя. */
    public void status(long chatId) {
        try {
            StatusService.UserStatus status = statusService.getUserStatus(chatId);

            if (status.totalTasks() == 0 && status.activeDownloads() == 0) {
                showMenu(chatId, "🎉 Активных задач нет.\n\nВыберите действие:");
                return;
            }

            StringBuilder message = new StringBuilder("📊 <b>Статус обработки</b>\n\n")
                    .append("⏳ Ожидает загрузки: ").append(status.pendingTasks()).append('\n')
                    .append("🔄 Ожидает транскрипции: ").append(status.processingTasks()).append('\n')
                    .append("📥 Активных загрузок: ").append(status.activeDownloads()).append('\n')
                    .append("📋 Всего задач: ").append(status.totalTasks()).append('\n');

            if (status.activeDownloads() > 0) {
                message.append("\n🔗 <b>Скачивается сейчас:</b>\n");
                for (DownloadService.DownloadInfo download : status.downloads()) {
                    long minutes = (System.currentTimeMillis() - download.startTime()) / 60_000;
                    message.append("• ").append(MessageSender.escapeHtml(download.url()))
                            .append(" (").append(minutes).append(" мин)\n");
                }
            }

            message.append("\n⏱️ Всё считается в фоне — уведомлю по готовности.");
            messageSender.sendMessageWithKeyboard(chatId, message.toString(), "HTML", Keyboards.mainMenu());

        } catch (Exception e) {
            log.error("Ошибка получения статуса: chatId={}", chatId, e);
            messageSender.sendMessage(chatId, "❌ Не удалось получить статус. Попробуйте позже.");
        }
    }
}

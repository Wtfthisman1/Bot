package Bot.handler;

/**
 * Экраны бота: приветствие, запрос ссылки, справка, форма загрузки, статус.
 *
 * <p>Ответственность: собрать текст экрана и отправить его вместе с нужной
 * клавиатурой. Каждый экран — публичный метод, поэтому и текстовая команда
 * ({@code /start}), и нажатие кнопки ({@link CallbackHandler}) приводят к
 * одному и тому же результату: тексты не расходятся между двумя путями.</p>
 *
 * <p>Связан с {@link HomeApi} (ссылка на форму и сводка задач приходят оттуда),
 * {@link MessageSender}, {@link UserSessionService}.</p>
 */
import Bot.handler.UserSessionService.Mode;
import Bot.home.HomeApi;
import Bot.insight.InsightKind;
import Bot.owner.Owner;
import Bot.processing.MediaKind;
import Bot.telegram.Keyboards;
import Bot.telegram.MessageSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class CommandHandler {

    /**
     * Нагрузка {@code /start}, которой страница входа зовёт выдать ссылку.
     *
     * <p>Секрета не несёт: что бы в ней ни пришло, ссылка выдаётся тому чату,
     * который её попросил. Поэтому подсунуть её чужому человеку бессмысленно —
     * он получит вход в свой же аккаунт.</p>
     */
    private static final String LOGIN_PAYLOAD = "login";

    private final HomeApi home;
    private final MessageSender messageSender;
    private final UserSessionService sessionService;

    /** Маршрутизация текстовых команд на те же экраны, что и кнопки. */
    public void handleCommand(long chatId, String text, String name) {
        // «/start@MyBot» и «/start payload» — тоже /start
        String command = text.split("[\\s@]", 2)[0].toLowerCase();
        log.info("Команда: chatId={}, команда='{}'", chatId, command);

        switch (command) {
            // Единственная полезная нагрузка — слово «login» со страницы входа,
            // и в ней нет никакого секрета: бот выдаёт ссылку тому чату, который
            // её попросил. Раньше здесь приходило «/start login_<код>» или
            // «/start link_<код>», и человек одним нажатием подтверждал
            // действие, начатое кем-то другим, — такую ссылку можно было
            // прислать постороннему под любым предлогом
            case "/start" -> {
                if (LOGIN_PAYLOAD.equals(payloadOf(text))) {
                    login(chatId, name);
                } else {
                    start(chatId, name);
                }
            }
            case "/help" -> help(chatId);
            case "/transcribe" -> askForLink(chatId, Mode.TRANSCRIBE, MediaKind.AUDIO);
            case "/download" -> askDownloadKind(chatId);
            case "/upload" -> upload(chatId);
            case "/status" -> status(chatId);
            case "/login" -> login(chatId, name);
            case "/link" -> link(chatId, text);
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
                Присылайте голосовое, кружок, аудио, видео или ссылку.

                📥 <b>Скачать</b> — аудио или видео по ссылке, формат выбираете кнопкой.
                В ответ придёт ссылка на файл, действительна 24 часа.

                📤 <b>Загрузить файлы</b> — форма для файлов больше 20 МБ.
                До 5 файлов, до 2500 МБ каждый.

                ✨ <b>Выжимка</b> — кнопка под готовой расшифровкой.
                Модель прочитает запись и перескажет, о чём она была, с метками
                времени.

                🔎 <b>По теме</b> — соседняя кнопка. Спросит, что искать, и
                соберёт всё, что об этом говорили, — даже там, где тему не
                называют прямо.

                И то, и другое считается на той же видеокарте, что и
                расшифровки, поэтому ответ приходит через несколько минут,
                отдельным сообщением.

                📦 <b>Ограничения:</b>
                • До 20 МБ — можно прямо в чат
                • Больше 20 МБ — Telegram не отдаёт файл боту, нужна форма загрузки
                • Расшифровок — три в месяц; скачивание в лимит не входит

                🔐 <b>Сайт</b> — та же история задач и тот же лимит.
                Вход без пароля и без номера телефона: кнопка «Войти на сайт»
                здесь или команда <code>/login</code>. Бот пришлёт ссылку —
                откройте её, и кабинет откроется сам.
                Если аккаунт на сайте уже заведён иначе, возьмите код в кабинете
                и пришлите его сюда: <code>/link КОД</code>

                🔗 <b>Ссылки:</b> YouTube, Vimeo, TikTok, Instagram, Twitter/X, Facebook

                ⏱️ Обработка идёт в фоне: голосовое — 1–3 мин, видео — от 5 мин.
                """;
        messageSender.sendMessageWithKeyboard(chatId, help.strip(), "HTML", Keyboards.mainMenu());
    }

    /**
     * Просит тему для разбора уже готовой расшифровки.
     *
     * <p>Второй шаг, а не сразу заказ: тему невозможно уместить в кнопку — это
     * произвольный текст, ради которого разбор и заведён. Расшифровка на это
     * время лежит в состоянии диалога, как и отложенная ссылка.</p>
     */
    public void askTopic(long chatId, String jobId) {
        sessionService.awaitTopic(chatId, jobId);
        messageSender.sendMessageWithKeyboard(chatId,
                "🔎 Что найти в этой записи?\n\n"
                        + "Напишите тему одной строкой — например, «сроки и деньги». "
                        + "Модель соберёт всё, что об этом говорили, даже там, где "
                        + "тему не называют прямо.",
                null, Keyboards.cancel());
    }

    /**
     * Заказывает обработку расшифровки моделью.
     *
     * <p>Общий путь для кнопки «Выжимка» и для присланной темы: отказ и
     * подтверждение должны звучать одинаково, откуда бы заказ ни пришёл.</p>
     *
     * <p>Текста ответа здесь не будет: модель считает минутами, и ждать её в
     * обработчике нажатия нельзя. Готовое пришлёт дом отдельным сообщением.</p>
     */
    public void orderInsight(long chatId, String jobId, InsightKind kind, String topic) {
        log.info("Заказана обработка из чата: chatId={}, jobId={}, вид={}", chatId, jobId, kind);
        Optional<String> refusal = home.orderInsight(Owner.telegram(chatId), jobId, kind, topic);
        if (refusal.isPresent()) {
            showMenu(chatId, "🤷 " + refusal.get());
            return;
        }

        showMenu(chatId, "%s Считаю. Пришлю сюда — на час записи уходит несколько минут."
                .formatted(kind == InsightKind.SUMMARY ? "✨" : "🔎"));
    }

    /** Выдаёт одноразовую ссылку на форму загрузки. */
    public void upload(long chatId) {
        messageSender.sendChatAction(chatId, "typing");

        String link = home.uploadFormLink(Owner.telegram(chatId));
        log.info("Выдана ссылка на форму загрузки: chatId={}", chatId);

        String html = """
                📤 <b>Форма загрузки</b>

                До 5 файлов или ссылок, до 2500 МБ каждый.
                Ссылка одноразовая и действует 1 час.

                <a href="%s">Открыть форму загрузки</a>
                """.formatted(MessageSender.escapeHtml(link));

        messageSender.sendMessage(chatId, html.strip(), "HTML");
    }

    /**
     * Выдаёт одноразовую ссылку на вход в кабинет.
     *
     * <p>Вход начинается здесь, а не на странице сайта, и это главное. Ссылка
     * рождается из этого чата и приходит только в него: прислать постороннему
     * нечего — ссылки на чужой аккаунт не существует. Раньше было наоборот:
     * страница заводила код, а подтверждать шли в чат, и достаточно было
     * прислать человеку ссылку под благовидным предлогом, чтобы он подтвердил
     * чужой вход.</p>
     *
     * <p>Имя из чата едет вместе с просьбой: его покажет страница входа, чтобы
     * человек видел, в какой аккаунт его пускают.</p>
     */
    public void login(long chatId, String name) {
        messageSender.sendChatAction(chatId, "typing");

        Optional<String> link;
        try {
            link = home.loginLink(chatId, name);
        } catch (Exception e) {
            log.error("Не удалось выдать ссылку входа: chatId={}", chatId, e);
            showMenu(chatId, "🌙 Сейчас вход на сайт не выдать — рабочая машина недоступна.");
            return;
        }

        if (link.isEmpty()) {
            showMenu(chatId, "🕓 Слишком много ссылок за раз. Подождите немного и попробуйте снова.");
            return;
        }

        log.info("Ссылка входа отправлена в чат: chatId={}", chatId);
        String html = """
                🔐 <b>Вход в кабинет</b>

                <a href="%s">Открыть кабинет</a>

                Ссылка действует 3 минуты и срабатывает один раз.
                ❗ Никому её не пересылайте: кто откроет — тот и войдёт
                в ваш аккаунт.
                """.formatted(MessageSender.escapeHtml(link.get()));

        messageSender.sendMessage(chatId, html.strip(), "HTML");
    }

    /**
     * Привязка переписки к аккаунту сайта: {@code /link КОД}.
     *
     * <p>Код человек берёт в кабинете. После привязки задачи из чата видны на
     * сайте, а квота у них общая — до этого момента бот знает только chat id и
     * считает такую переписку отдельным человеком.</p>
     */
    public void link(long chatId, String text) {
        String[] parts = text.trim().split("\\s+", 2);
        if (parts.length < 2 || parts[1].isBlank()) {
            messageSender.sendMessage(chatId,
                    "🔗 Пришлите код так: <code>/link КОД</code>\n\n"
                            + "Код выдаёт кабинет на сайте — кнопка «Получить код привязки».",
                    "HTML");
            return;
        }

        redeemLink(chatId, parts[1].trim());
    }

    /**
     * Гасит код привязки и отвечает человеку.
     *
     * <p>Код человек набирает руками, глядя в свой кабинет. Кнопки «Да, это мой
     * аккаунт» под присланной ссылкой больше нет: нажатие подтверждало
     * действие, которое начал кто-то другой, и посторонний одним касанием
     * отдавал свою переписку вместе со всеми расшифровками.</p>
     */
    public void redeemLink(long chatId, String code) {
        try {
            home.linkTelegram(chatId, code).ifPresentOrElse(
                    title -> {
                        log.info("Чат привязан к аккаунту: chatId={}", chatId);
                        showMenu(chatId, "✅ Готово. Этот чат теперь принадлежит аккаунту «"
                                + title + "» — задачи и лимит у них общие.");
                    },
                    () -> messageSender.sendMessage(chatId,
                            "❌ Код не подошёл: он мог устареть или уже сработать. "
                                    + "Возьмите новый в кабинете."));
        } catch (Exception e) {
            log.error("Не удалось привязать чат к аккаунту: chatId={}", chatId, e);
            messageSender.sendMessage(chatId,
                    "🌙 Сейчас привязку сделать не получится — рабочая машина недоступна. "
                            + "Попробуйте позже.");
        }
    }

    /** Короткая сводка по задачам пользователя. */
    public void status(long chatId) {
        try {
            HomeApi.OwnerStatus status = home.status(Owner.telegram(chatId));

            if (status.total() == 0 && status.downloads().isEmpty()) {
                showMenu(chatId, "🎉 Активных задач нет.\n\nВыберите действие:");
                return;
            }

            // Названия состояний прямые: раньше здесь стояло «ожидает
            // транскрипции» напротив числа тех, что расшифровываются прямо
            // сейчас, и сводка противоречила истории на сайте
            StringBuilder message = new StringBuilder("📊 <b>Статус обработки</b>\n\n")
                    .append("⏳ Ждут очереди: ").append(status.queued()).append('\n')
                    .append("⚙️ Считаются сейчас: ").append(status.running()).append('\n')
                    .append("📋 Всего задач: ").append(status.total()).append('\n');

            if (!status.jobs().isEmpty()) {
                message.append('\n');
                for (HomeApi.ActiveJob job : status.jobs()) {
                    message.append(job.running() ? "⚙️ " : "⏳ ")
                            .append(MessageSender.escapeHtml(job.title()))
                            .append(job.download() ? " — скачивание" : "")
                            .append('\n');
                }
            }

            if (!status.downloads().isEmpty()) {
                message.append("\n🔗 <b>Скачивается сейчас:</b>\n");
                for (HomeApi.ActiveDownload download : status.downloads()) {
                    long minutes = Duration.between(download.startedAt(), Instant.now()).toMinutes();
                    message.append("• ").append(MessageSender.escapeHtml(download.url()))
                            .append(" (").append(minutes).append(" мин)\n");
                }
            }

            message.append("\n⏱️ Всё считается в фоне — уведомлю по готовности.");

            // Кнопки только если есть что останавливать: пустая клавиатура из
            // одного «в меню» под сводкой ничего не добавляет
            List<Keyboards.Job> cancellable = status.jobs().stream()
                    .map(job -> new Keyboards.Job(job.id(), job.title()))
                    .toList();
            messageSender.sendMessageWithKeyboard(chatId, message.toString(), "HTML",
                    cancellable.isEmpty() ? Keyboards.mainMenu() : Keyboards.activeJobs(cancellable));

        } catch (Exception e) {
            // Статус — вспомогательный экран: молчать хуже, чем признаться,
            // что сводку сейчас не достать
            log.error("Ошибка получения статуса: chatId={}", chatId, e);
            messageSender.sendMessage(chatId, "❌ Не удалось получить статус. Попробуйте позже.");
        }
    }

    /**
     * Останавливает задачу по кнопке из сводки.
     *
     * <p>Отказ здесь — не поломка: пока человек смотрел на сводку, задача могла
     * доделаться, и тогда останавливать уже нечего. Сказать об этом надо
     * прямо, иначе кнопка выглядит сломанной.</p>
     */
    public void cancelJob(long chatId, String jobId) {
        log.info("Запрошена остановка задачи: chatId={}, jobId={}", chatId, jobId);
        if (home.cancelJob(Owner.telegram(chatId), jobId)) {
            showMenu(chatId, "⛔ Остановлено. Лимит расшифровок эта задача не потратила.");
            return;
        }
        showMenu(chatId, "🤷 Эту задачу уже не остановить — она успела доделаться или снята раньше.");
    }


    /** То, что идёт после команды: «/start login» → «login». */
    private static String payloadOf(String text) {
        String[] parts = text.trim().split("\\s+", 2);
        return parts.length < 2 ? "" : parts[1].trim();
    }
}

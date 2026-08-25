package Bot.telegram;

/**
 * Основной Telegram-бот (LongPolling).
 *
 * <p>Ответственность: регистрирует команды, принимает апдейты и делегирует их
 * {@link MessageHandler}, {@link CommandHandler} и {@link CallbackHandler}.
 * Зависит от {@link Bot.config.BotConfig}. Поднимается только в профиле
 * {@code bot}: getUpdates Telegram отдаёт одному процессу, поэтому приём живёт
 * там, где стоит бот, а отправка — в {@link TelegramApi}, доступном всем.</p>
 *
 * Ключевые методы: {@code init} (регистрация команд), {@code onUpdateReceived}.</p>
 */
import Bot.config.BotConfig;
import Bot.config.Profiles;
import Bot.handler.CallbackHandler;
import Bot.home.HomeUnavailableException;
import Bot.handler.CommandHandler;
import Bot.handler.MessageHandler;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.api.objects.commands.scope.BotCommandScopeDefault;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.List;

@Profile(Profiles.BOT)
@Component
@Slf4j
public class TelegramBot extends TelegramLongPollingBot {

    /** Ключ MDC: попадает в каждую строку лога этой обработки. */
    public static final String MDC_CHAT_ID = "chatId";

    /**
     * Нативное меню команд Telegram. Дублирует кнопки меню: те же действия
     * доступны и из «/»-подсказки клиента, и инлайн-кнопкой.
     */
    private static final List<BotCommand> COMMANDS = List.of(
            new BotCommand("/start", "Начать и открыть меню"),
            new BotCommand("/transcribe", "Транскрибировать по ссылке"),
            new BotCommand("/download", "Скачать по ссылке"),
            new BotCommand("/upload", "Загрузить файлы через форму"),
            new BotCommand("/status", "Статус обработки"),
            new BotCommand("/help", "Справка")
    );

    private final BotConfig config;
    private final MessageHandler messageHandler;
    private final CommandHandler commandHandler;
    private final CallbackHandler callbackHandler;
    private final MessageSender messageSender;

    public TelegramBot(BotConfig cfg,
                       MessageHandler messageHandler,
                       CommandHandler commandHandler,
                       CallbackHandler callbackHandler,
                       MessageSender messageSender) {
        super(cfg.getBotToken());
        this.config = cfg;
        this.messageHandler = messageHandler;
        this.commandHandler = commandHandler;
        this.callbackHandler = callbackHandler;
        this.messageSender = messageSender;
    }

    /* ───────────────── init ───────────────── */
    @PostConstruct
    void init() {
        try {
            execute(new SetMyCommands(COMMANDS, new BotCommandScopeDefault(), null));
        } catch (TelegramApiException e) {
            log.error("Не удалось зарегистрировать команды", e);
        }
    }

    @Override
    public String getBotUsername() {
        return config.getBotName();
    }

    /* ───────────────── updates ───────────────── */
    @Override
    public void onUpdateReceived(Update u) {
        if (u.hasCallbackQuery()) {
            handleCallback(u.getCallbackQuery());
            return;
        }

        if (!u.hasMessage()) {
            log.debug("Апдейт без сообщения и callback пропущен: updateId={}", u.getUpdateId());
            return;
        }

        long chatId = u.getMessage().getChatId();
        String name = firstName(u.getMessage().getFrom());

        // chatId попадает во все логи этой обработки, включая вложенные вызовы
        MDC.put(MDC_CHAT_ID, String.valueOf(chatId));
        try {
            routeMessage(u.getMessage(), chatId, name);
        } catch (HomeUnavailableException e) {
            homeAsleep(chatId, e);
        } catch (Exception e) {
            // Иначе исключение уходит в библиотеку long-polling и теряется
            log.error("Необработанная ошибка при разборе апдейта: updateId={}", u.getUpdateId(), e);
        } finally {
            MDC.remove(MDC_CHAT_ID);
        }
    }

    /** Разбирает тип сообщения и передаёт его нужному обработчику. */
    private void routeMessage(Message message, long chatId, String name) {
        if (message.hasText()) {
            String text = message.getText();
            if (text.startsWith("/")) {
                commandHandler.handleCommand(chatId, text, name);
            } else {
                log.info("Текстовое сообщение: chatId={}, длина={}", chatId, text.length());
                messageHandler.handleText(chatId, text, name);
            }
        } else if (message.hasVoice()) {
            messageHandler.handleVoice(chatId, message.getVoice(), name);
        } else if (message.hasAudio()) {
            messageHandler.handleAudio(chatId, message.getAudio(), name);
        } else if (message.hasVideo()) {
            messageHandler.handleVideo(chatId, message.getVideo(), name);
        } else if (message.hasDocument()) {
            messageHandler.handleDocument(chatId, message.getDocument(), name);
        } else {
            // Стикеры, локации, контакты и прочее — бот их не умеет, но знать об этом полезно
            log.info("Тип сообщения не поддерживается и проигнорирован: chatId={}", chatId);
        }
    }

    /* ───────────────── callback ───────────────── */
    private void handleCallback(CallbackQuery cq) {
        // Гасим «часики» на кнопке до обработки: иначе кнопка выглядит зависшей,
        // пока идёт работа, а через 10 секунд Telegram считает callback протухшим
        answerCallback(cq);

        // getMessage() может быть null для старых сообщений — берём chatId из отправителя
        Long chatId = (cq.getMessage() != null)
                ? cq.getMessage().getChatId()
                : (cq.getFrom() != null ? cq.getFrom().getId() : null);

        if (chatId == null) {
            log.warn("Не удалось определить chatId для callback {}", cq.getId());
            return;
        }

        MDC.put(MDC_CHAT_ID, String.valueOf(chatId));
        try {
            // INFO, а не DEBUG: без этой строки нажатие кнопки не оставляет следа
            // в проде и «кнопка не работает» невозможно отличить от «апдейт не дошёл»
            log.info("Callback получен: chatId={}, data='{}'", chatId, cq.getData());
            callbackHandler.handle(chatId, cq.getData(), firstName(cq.getFrom()));
        } catch (HomeUnavailableException e) {
            homeAsleep(chatId, e);
        } catch (Exception e) {
            log.error("Необработанная ошибка при разборе callback: chatId={}", chatId, e);
        } finally {
            MDC.remove(MDC_CHAT_ID);
        }
    }

    /**
     * Дом не отвечает — единственное место, где это превращается в ответ.
     *
     * <p>Ловится здесь, а не в каждом обработчике: любое действие пользователя
     * упирается в дом, и разбросанные по экранам одинаковые try/catch только
     * прятали бы причину. Ниже по стеку остаётся лишь то, что работает в
     * фоне и до этого catch не доходит.</p>
     */
    private void homeAsleep(long chatId, HomeUnavailableException e) {
        log.warn("Домашняя машина не отвечает: chatId={}, причина={}", chatId, e.getMessage());
        messageSender.sendMessageWithKeyboard(chatId, HomeUnavailableException.USER_MESSAGE,
                null, Keyboards.mainMenu());
    }

    private void answerCallback(CallbackQuery cq) {
        try {
            execute(AnswerCallbackQuery.builder()
                    .callbackQueryId(cq.getId())
                    .build());
        } catch (TelegramApiException e) {
            log.debug("Не удалось ответить на callbackQuery", e);
        }
    }

    private String firstName(User user) {
        return user != null ? user.getFirstName() : null;
    }
}

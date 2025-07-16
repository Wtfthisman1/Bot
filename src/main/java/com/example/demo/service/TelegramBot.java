package com.example.demo.service;

import com.example.demo.config.BotConfig;
import com.example.demo.upload.UploadService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.methods.send.SendChatAction;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.api.objects.commands.scope.BotCommandScopeDefault;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.nio.file.Path;
import java.util.List;

@Component
@Slf4j
public class TelegramBot extends TelegramLongPollingBot {

    private static final int TG_LIMIT = 4096;

    private final BotConfig      config;
    private final UploadService  uploadService;
    private final TaskExecutor   taskExecutor;   // дефолтный пул Spring Boot
    private final List<BotCommand> commands;

    public TelegramBot(BotConfig cfg,
                       UploadService uploadService,
                       TaskExecutor taskExecutor) {
        super(cfg.getBotToken());
        this.config        = cfg;
        this.uploadService = uploadService;
        this.taskExecutor  = taskExecutor;

        this.commands = List.of(
                new BotCommand("/start",  "Старт"),
                new BotCommand("/help",   "Справка"),
                new BotCommand("/upload", "Получить ссылку")
        );
    }

    /* ───────────────── init ───────────────── */
    @PostConstruct
    void init() {
        try {
            execute(new SetMyCommands(commands, new BotCommandScopeDefault(), null));
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
        if (!u.hasMessage() || !u.getMessage().hasText()) return;

        long   chatId = u.getMessage().getChatId();
        String text   = u.getMessage().getText();

        switch (text) {
            case "/start"  -> sendMessage(chatId, "Привет!");
            case "/help"   -> sendMessage(chatId,
                    "Отправьте /upload, чтобы получить ссылку на форму загрузки файлов.");
            case "/upload" -> uploadCommand(chatId);
            default        -> sendMessage(chatId, "Не понимаю 🤔");
        }
    }

    /* ───────────────── helpers ───────────────── */

    private void uploadCommand(long chatId) {
        // небольшое UX-уведомление «печатаю»
        sendChatAction(chatId, "typing");

        String link = uploadService.generate(chatId);
        // Используем только поддерживаемые Telegram HTML-теги и \n для переносов строк
        String html = """
                Вы можете загрузить до <b>5</b> файлов или ссылок.\n
                Ссылка действительна 1 час и только один раз.\n\n
                <a href=\"%s\">Перейти к форме загрузки</a>
                """.formatted(link);

        sendMessage(chatId, html.strip(), "HTML");
    }

    /** Асинхронная отправка готовой транскрипции */
    public void sendTranscript(long chatId, Path txt) {
        taskExecutor.execute(() -> {
            try {
                execute(SendDocument.builder()
                        .chatId(String.valueOf(chatId))
                        .caption("✅ Ваша транскрипция")
                        .document(new InputFile(txt.toFile()))
                        .build());
            } catch (TelegramApiException e) {
                log.error("Ошибка отправки файла", e);
            }
        });
    }

    /* base sendMessage */

    public void sendMessage(long chatId, String text) {
        sendMessage(chatId, text, null);
    }

    public void sendMessage(long chatId, String text, String parseMode) {
        if (text.length() > TG_LIMIT)
            text = text.substring(0, TG_LIMIT - 3) + "…";

        SendMessage msg = SendMessage.builder()
                .chatId(String.valueOf(chatId))
                .text(text)
                .build();
        if (parseMode != null) msg.setParseMode(parseMode);

        // Сделать переменные effectively final, чтобы не было warning в лямбде
        final String outboundText = text;
        final SendMessage outboundMsg = msg;

        taskExecutor.execute(() -> {
            try {
                if (log.isDebugEnabled()) {
                    log.debug("OUT ▶ {}", outboundText.replace("\n", "\\n"));
                }
                execute(outboundMsg);
            } catch (TelegramApiException e) {
                log.error("Ошибка sendMessage", e);
            }
        });
    }

    /* показать 'typing', 'upload_document' и т. п. */
    private void sendChatAction(long chatId, String action) {
        taskExecutor.execute(() -> {
            try {
                execute(SendChatAction.builder()
                        .chatId(String.valueOf(chatId))
                        .action(action)
                        .build());
            } catch (TelegramApiException ignored) {}
        });
    }
}

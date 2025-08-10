package com.example.demo.service;

import com.example.demo.config.BotConfig;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.api.objects.commands.scope.BotCommandScopeDefault;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.List;

@Component
@Slf4j
public class TelegramBot extends TelegramLongPollingBot {

    private final BotConfig config;
    private final MessageHandler messageHandler;
    private final CommandHandler commandHandler;
    private final MessageSender messageSender;
    private final List<BotCommand> commands;

    public TelegramBot(BotConfig cfg,
                       MessageHandler messageHandler,
                       CommandHandler commandHandler,
                       MessageSender messageSender) {
        super(cfg.getBotToken());
        this.config = cfg;
        this.messageHandler = messageHandler;
        this.commandHandler = commandHandler;
        this.messageSender = messageSender;

        this.commands = List.of(
                new BotCommand("/start",  "Старт"),
                new BotCommand("/help",   "Справка"),
                new BotCommand("/upload", "Получить ссылку"),
                new BotCommand("/status", "Статус"),
                new BotCommand("/transcripts", "Мои транскрипции")
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
        if (!u.hasMessage()) return;

        long chatId = u.getMessage().getChatId();
        String name = u.getMessage().getFrom().getFirstName();

        // Обработка текстовых сообщений
        if (u.getMessage().hasText()) {
            String text = u.getMessage().getText();
            if (text.startsWith("/")) {
                commandHandler.handleCommand(chatId, text, name);
            } else {
                messageHandler.handleText(chatId, text, name);
            }
            return;
        }

        // Обработка голосовых сообщений
        if (u.getMessage().hasVoice()) {
            messageHandler.handleVoice(chatId, u.getMessage().getVoice(), name);
            return;
        }

        // Обработка аудио файлов
        if (u.getMessage().hasAudio()) {
            messageHandler.handleAudio(chatId, u.getMessage().getAudio(), name);
            return;
        }

        // Обработка видео файлов
        if (u.getMessage().hasVideo()) {
            messageHandler.handleVideo(chatId, u.getMessage().getVideo(), name);
            return;
        }

        // Обработка документов
        if (u.getMessage().hasDocument()) {
            messageHandler.handleDocument(chatId, u.getMessage().getDocument(), name);
            return;
        }
    }
}

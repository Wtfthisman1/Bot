package com.example.demo.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.send.SendChatAction;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.nio.file.Path;

@Service
@RequiredArgsConstructor
@Slf4j
public class MessageSender {

    private static final int TG_LIMIT = 4096;

    private final TaskExecutor taskExecutor;
    private final ApplicationContext applicationContext; // Используем ApplicationContext

    /**
     * Получает TelegramBot из контекста
     */
    private TelegramBot getTelegramBot() {
        return applicationContext.getBean(TelegramBot.class);
    }

    /**
     * Отправляет текстовое сообщение
     */
    public void sendMessage(long chatId, String text) {
        sendMessage(chatId, text, null);
    }

    /**
     * Отправляет текстовое сообщение с форматированием
     */
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
                getTelegramBot().execute(outboundMsg);
            } catch (TelegramApiException e) {
                log.error("Ошибка sendMessage", e);
            }
        });
    }

    /**
     * Отправляет транскрипцию как документ
     */
    public void sendTranscript(long chatId, Path txt) {
        taskExecutor.execute(() -> {
            try {
                getTelegramBot().execute(SendDocument.builder()
                        .chatId(String.valueOf(chatId))
                        .caption("✅ Ваша транскрипция")
                        .document(new InputFile(txt.toFile()))
                        .build());
            } catch (TelegramApiException e) {
                log.error("Ошибка отправки файла", e);
            }
        });
    }

    /**
     * Отправляет сообщение с инлайн клавиатурой
     */
    public void sendMessageWithKeyboard(long chatId, String text, String parseMode, InlineKeyboardMarkup keyboard) {
        if (text.length() > TG_LIMIT)
            text = text.substring(0, TG_LIMIT - 3) + "…";

        SendMessage msg = SendMessage.builder()
                .chatId(String.valueOf(chatId))
                .text(text)
                .replyMarkup(keyboard)
                .build();
        if (parseMode != null) msg.setParseMode(parseMode);

        final String outboundText = text;
        final SendMessage outboundMsg = msg;

        taskExecutor.execute(() -> {
            try {
                if (log.isDebugEnabled()) {
                    log.debug("OUT ▶ {}", outboundText.replace("\n", "\\n"));
                }
                getTelegramBot().execute(outboundMsg);
            } catch (TelegramApiException e) {
                log.error("Ошибка sendMessageWithKeyboard", e);
            }
        });
    }

    /**
     * Показывает действие пользователя (typing, upload_document и т. п.)
     */
    public void sendChatAction(long chatId, String action) {
        taskExecutor.execute(() -> {
            try {
                getTelegramBot().execute(SendChatAction.builder()
                        .chatId(String.valueOf(chatId))
                        .action(action)
                        .build());
            } catch (TelegramApiException ignored) {}
        });
    }
}

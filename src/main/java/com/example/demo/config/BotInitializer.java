package com.example.demo.config;

import com.example.demo.logging.TelegramLogAppender;
import com.example.demo.service.MessageSender;
import com.example.demo.service.TelegramBot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

/**
 * Регистрирует Telegram-бота в API после того, как Spring полностью поднялся.
 */
@Slf4j
@Configuration      // или @Component
@RequiredArgsConstructor
public class BotInitializer {

    private final TelegramBot bot;
    private final MessageSender messageSender;

    @EventListener(ContextRefreshedEvent.class)
    public void init() {
        try {
            TelegramBotsApi api = new TelegramBotsApi(DefaultBotSession.class);
            api.registerBot(bot);
            TelegramLogAppender.init(messageSender);
            log.info("Telegram bot registered and log appender initialized");
        } catch (TelegramApiException e) {
            log.error("Failed to register Telegram bot", e);
        }
    }
}

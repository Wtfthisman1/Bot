package com.example.demo.config;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;

@Configuration
@Data
@Slf4j
public class BotConfig {
    @Value("${bot.name}")
    String botName ;
    @Value("${bot.key}")
    String botToken ;

    @Value("${admin.chat.id}")
    String adminChatId;

    @PostConstruct
    public void logConfig() {
        log.info("=== Конфигурация бота ===");
        log.info("Bot Name: {}", botName);
        log.info("Bot Token: {}", botToken != null ? botToken.substring(0, Math.min(10, botToken.length())) + "..." : "NULL");
        log.info("Admin Chat ID: {}", adminChatId);
        log.info("=========================");
    }
}

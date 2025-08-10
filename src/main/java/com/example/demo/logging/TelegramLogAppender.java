package com.example.demo.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.example.demo.service.MessageSender;
import org.slf4j.MDC;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Аппендер, отправляющий сообщения уровня ERROR+ администратору в Telegram.
 */
public class TelegramLogAppender extends AppenderBase<ILoggingEvent> {

    /** Синглтон-ссылка на MessageSender, устанавливается из Spring. */
    private static volatile MessageSender messageSender;

    /** Chat-ID администратора — задаём сразу константой. */
    private static final long ADMIN_CHAT_ID = 6063832614L;   // ← замените на реальный ID

    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                    .withZone(ZoneId.systemDefault());

    /*────────────────────  Инъекция  ────────────────────*/

    /** Вызывается из Spring (например, в TelegramBot.@PostConstruct). */
    public static void init(MessageSender messageSenderBean) {
        messageSender = messageSenderBean;
    }

    /*──────────────────  Основная логика  ──────────────────*/

    @Override
    protected void append(ILoggingEvent event) {
        if (messageSender == null) return;
        if (!event.getLevel().isGreaterOrEqual(Level.ERROR)) return;

        String timestamp = TS_FMT.format(Instant.ofEpochMilli(event.getTimeStamp()));

        String user   = MDC.get("user");
        String chatId = MDC.get("chatId");

        StringBuilder sb = new StringBuilder()
                .append("❗ *Ошибка в приложении*\n")
                .append("🕒 `").append(timestamp).append("`\n");

        if (user != null || chatId != null) {
            sb.append("👤 ");
            if (user   != null) sb.append("*").append(escape(user)).append("* ");
            if (chatId != null) sb.append("(`").append(chatId).append("`)");
            sb.append('\n');
        }

        sb.append("\n```")
                .append(escape(event.getFormattedMessage()))
                .append("```");

        String message = truncate(sb.toString(), 4096);

        try {
            messageSender.sendMessage(ADMIN_CHAT_ID, message, "Markdown");
        } catch (Exception ex) {
            addError("Failed to send log to Telegram", ex);
        }
    }

    /*──────────────────  Вспомогательные методы  ──────────────────*/

    private static String escape(String src) {
        return src.replaceAll("([_\\*\\[\\]()~`>#+\\-=|{}.!])", "\\\\$1");
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s
                : s.substring(0, max - 3) + "...";
    }
}

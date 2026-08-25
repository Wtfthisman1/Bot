package Bot.telegram;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Аппендер Logback, отправляющий ERROR+ сообщения администратору в Telegram.
 *
 * <p>Ответственность: форматирование события лога, извлечение контекста из MDC
 * и отправка через {@link MessageSender}. Инициализация (включая chatId
 * администратора) происходит через статический {@code init} из Spring-контекста.</p>
 *
 * <p><b>Защита от лавины.</b> Одна загрузка с обрывами сети выдала админу восемь
 * «❗ Ошибка в приложении» подряд, хотя yt-dlp сам всё пересобрал и задача
 * завершилась успешно. Поэтому здесь три фильтра: пустые сообщения
 * отбрасываются, повтор того же текста подавляется на {@link #DEDUP_WINDOW},
 * а общий поток ограничен {@link #MAX_PER_WINDOW} сообщениями в минуту — что
 * сверх лимита, сводится в одну строку «подавлено N».</p>
 */
public class TelegramLogAppender extends AppenderBase<ILoggingEvent> {

    /** Один и тот же текст ошибки не повторяем в течение этого срока. */
    private static final Duration DEDUP_WINDOW = Duration.ofMinutes(5);

    /** Не больше стольких сообщений в минуту, чтобы не залить чат админа. */
    private static final int MAX_PER_WINDOW = 5;
    private static final long RATE_WINDOW_MS = 60_000;

    /** Синглтон-ссылка на MessageSender, устанавливается из Spring. */
    private static volatile MessageSender messageSender;

    /** Chat-ID администратора, прокидывается из конфигурации при инициализации. */
    private static volatile long adminChatId;

    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                    .withZone(ZoneId.systemDefault());

    /** текст ошибки → когда отправляли в последний раз. */
    private final Map<String, Instant> recentlySent = new ConcurrentHashMap<>();
    private final AtomicLong windowStartedAt = new AtomicLong(System.currentTimeMillis());
    private final AtomicInteger sentInWindow = new AtomicInteger();
    private final AtomicInteger suppressedInWindow = new AtomicInteger();

    /*────────────────────  Инъекция  ────────────────────*/

    /**
     * Вызывается из Spring (см. {@code BotInitializer}).
     *
     * @param messageSenderBean бин отправки сообщений
     * @param adminChatIdValue  chatId администратора; 0 означает «не настроен» —
     *                          в этом случае отправка ошибок отключается
     */
    public static void init(MessageSender messageSenderBean, long adminChatIdValue) {
        messageSender = messageSenderBean;
        adminChatId = adminChatIdValue;
    }

    /*──────────────────  Основная логика  ──────────────────*/

    @Override
    protected void append(ILoggingEvent event) {
        if (messageSender == null || adminChatId == 0L) return;
        if (!event.getLevel().isGreaterOrEqual(Level.ERROR)) return;

        String body = event.getFormattedMessage();
        // Пустые строки вроде «[YT-DLP] ERROR:» уходили админу как отдельные алерты
        if (body == null || body.isBlank() || stripMarkers(body).isBlank()) {
            return;
        }

        if (isDuplicate(body) || isOverRateLimit()) {
            return;
        }

        messageSender.sendMessage(adminChatId, render(event, body), "HTML");
    }

    /*──────────────────  Фильтры  ──────────────────*/

    private boolean isDuplicate(String body) {
        Instant now = Instant.now();
        Instant previous = recentlySent.put(body, now);

        // Заодно подчищаем карту, чтобы она не росла бесконечно
        recentlySent.entrySet().removeIf(e -> e.getValue().plus(DEDUP_WINDOW).isBefore(now));

        return previous != null && previous.plus(DEDUP_WINDOW).isAfter(now);
    }

    /**
     * Скользящее окно на минуту. По его закрытию, если что-то подавили,
     * отправляется одна сводка — иначе админ не узнает, что ошибок было больше.
     */
    private boolean isOverRateLimit() {
        long now = System.currentTimeMillis();
        long started = windowStartedAt.get();

        if (now - started >= RATE_WINDOW_MS && windowStartedAt.compareAndSet(started, now)) {
            int suppressed = suppressedInWindow.getAndSet(0);
            sentInWindow.set(0);
            if (suppressed > 0) {
                messageSender.sendMessage(adminChatId,
                        "❗ Подавлено однотипных ошибок за минуту: " + suppressed, null);
            }
        }

        if (sentInWindow.incrementAndGet() > MAX_PER_WINDOW) {
            suppressedInWindow.incrementAndGet();
            return true;
        }
        return false;
    }

    /*──────────────────  Форматирование  ──────────────────*/

    /**
     * Собирает сообщение в HTML.
     *
     * <p>Раньше текст экранировался по правилам MarkdownV2, а отправлялся с
     * {@code parse_mode=Markdown} — из-за несовпадения админ видел сообщения
     * вида {@code \[download\] Got error: \(host\='rr3\-\-\-sn...} с
     * обратными слэшами прямо в тексте.</p>
     */
    private String render(ILoggingEvent event, String body) {
        String timestamp = TS_FMT.format(Instant.ofEpochMilli(event.getTimeStamp()));
        Map<String, String> mdc = event.getMDCPropertyMap();
        String chatId = mdc.get("chatId");
        String jobId = mdc.get("jobId");

        StringBuilder sb = new StringBuilder()
                .append("❗ <b>Ошибка в приложении</b>\n")
                .append("🕒 ").append(timestamp).append('\n');

        if (chatId != null && !chatId.isBlank()) {
            sb.append("👤 chat ").append(MessageSender.escapeHtml(chatId)).append('\n');
        }
        if (jobId != null && !jobId.isBlank()) {
            sb.append("🧾 job ").append(MessageSender.escapeHtml(jobId)).append('\n');
        }

        sb.append("\n<pre>").append(MessageSender.escapeHtml(body)).append("</pre>");

        return truncate(sb.toString());
    }

    /** Отбрасывает служебные префиксы, чтобы распознать пустые по сути сообщения. */
    private static String stripMarkers(String body) {
        return body.replace("[YT-DLP]", "")
                .replace("[WHISPER]", "")
                .replace("ERROR:", "")
                .trim();
    }

    private static String truncate(String s) {
        if (s.length() <= 4096) {
            return s;
        }
        // Обрезаем внутри <pre>, чтобы тег остался закрытым и HTML не сломался
        return s.substring(0, 4096 - 12) + "…</pre>";
    }
}

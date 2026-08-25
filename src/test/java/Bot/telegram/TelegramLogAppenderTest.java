package Bot.telegram;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.util.LogbackMDCAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Обрывы сети в yt-dlp однажды выдали админу восемь «❗ Ошибка в приложении»
 * подряд по задаче, которая в итоге завершилась успешно. Эти тесты фиксируют
 * фильтры, которые такую лавину гасят.
 */
@ExtendWith(MockitoExtension.class)
class TelegramLogAppenderTest {

    private static final long ADMIN = REDACTED_CHAT_IDL;

    @Mock private MessageSender messageSender;

    private Logger logger;
    private LogbackMDCAdapter mdc;

    /**
     * Логируем через настоящий {@link Logger}, а не собираем событие руками:
     * MDC проставляется именно на этом пути, и тест повторяет то, что
     * происходит в приложении.
     */
    @BeforeEach
    void setUp() {
        TelegramLogAppender appender = new TelegramLogAppender();
        TelegramLogAppender.init(messageSender, ADMIN);

        // У голого LoggerContext нет MDC-адаптера, и без него создание события падает
        LoggerContext context = new LoggerContext();
        mdc = new LogbackMDCAdapter();
        context.setMDCAdapter(mdc);
        context.start();

        appender.setContext(context);
        appender.start();

        logger = context.getLogger("Bot.test");
        logger.setLevel(Level.DEBUG);
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        // messageSender в аппендере статический: без сброса фоновые потоки
        // чужих тестов продолжат писать в мок этого теста
        TelegramLogAppender.init(null, 0L);
        mdc.clear();
    }

    private void error(String message) {
        logger.error(message);
    }

    /**
     * Сообщения, отправленные админу, отфильтрованные по метке теста.
     *
     * <p>Тот же аппендер объявлен в боевом logback.xml и висит на логгере
     * {@code Bot}, поэтому в мок может прилететь ERROR от фоновых потоков
     * других тестов. Считаем только своё.</p>
     */
    private List<String> sentContaining(String marker) {
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(messageSender, atLeast(0)).sendMessage(eq(ADMIN), text.capture(), eq("HTML"));
        return text.getAllValues().stream().filter(v -> v.contains(marker)).toList();
    }

    @Test
    void errorIsForwardedToAdmin() {
        error("Скачивание не удалось: код=1 marker-forward");

        assertThat(sentContaining("marker-forward"))
                .singleElement().asString()
                .contains("Ошибка в приложении", "Скачивание не удалось");
    }

    @Test
    void levelsBelowErrorAreIgnored() {
        logger.warn("просто предупреждение");
        logger.info("просто информация");

        verify(messageSender, never()).sendMessage(anyLong(), any(), any());
    }

    /** Голые «[YT-DLP] ERROR:» приходили админу отдельными пустыми алертами. */
    @Test
    void blankAndMarkerOnlyMessagesAreDropped() {
        error("");
        error("   ");
        error("[YT-DLP] ERROR:");
        error("[WHISPER] ERROR:");

        verify(messageSender, never()).sendMessage(anyLong(), any(), any());
    }

    @Test
    void identicalErrorIsSentOnlyOnce() {
        String repeated = "marker-dedup Connection to rr3---sn-2gb6.googlevideo.com timed out";

        for (int i = 0; i < 8; i++) {
            error(repeated);
        }

        assertThat(sentContaining("marker-dedup")).hasSize(1);
    }

    /** Разные тексты не должны прорвать общий лимит на минуту. */
    @Test
    void burstOfDistinctErrorsIsRateLimited() {
        for (int i = 0; i < 20; i++) {
            error("marker-burst ошибка номер " + i);
        }

        assertThat(sentContaining("marker-burst")).hasSize(5);
    }

    /** Текст уходил с parse_mode=Markdown, а экранировался под MarkdownV2 — админ видел «\-\-\-». */
    @Test
    void messageIsHtmlEscapedNotMarkdownEscaped() {
        error("marker-html Got error: <HTTPSConnection host='rr3---sn-2gb6' & timed out>");

        assertThat(sentContaining("marker-html")).singleElement().asString()
                .contains("&lt;HTTPSConnection")
                .contains("&amp;")
                .contains("rr3---sn-2gb6")   // дефисы остаются как есть
                .doesNotContain("\\-");      // и никаких обратных слэшей
    }

    /** По алерту должно быть понятно, какая задача и чей чат его вызвали. */
    @Test
    void alertCarriesChatAndJobFromMdc() {
        mdc.put("chatId", "REDACTED_CHAT_ID");
        mdc.put("jobId", "1ac94145-766d-4bf7-8530-b9595af2b062");


        error("marker-mdc Скачивание не удалось: код=1");

        assertThat(sentContaining("marker-mdc")).singleElement().asString()
                .contains("REDACTED_CHAT_ID")
                .contains("1ac94145-766d-4bf7-8530-b9595af2b062");
    }

    @Test
    void nothingIsSentWhenAdminChatIsNotConfigured() {
        TelegramLogAppender.init(messageSender, 0L);

        error("важная ошибка");

        verify(messageSender, never()).sendMessage(anyLong(), any(), any());
    }
}

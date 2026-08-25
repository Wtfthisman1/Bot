package Bot.telegram;

/**
 * Асинхронная отправка сообщений и файлов в Telegram.
 *
 * <p>Ответственность: текстовые сообщения (с/без parse mode), документы,
 * инлайн-клавиатуры и chat action. Отправляет через {@link TelegramApi},
 * поэтому работает и там, где long polling не поднят, — на домашней машине
 * после разделения. Основные методы: {@code sendMessage},
 * {@code sendMessageWithKeyboard}, {@code sendTranscript}, {@code sendChatAction}.</p>
 */
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.send.SendChatAction;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class MessageSender {

    /** Жёсткий лимит Telegram — 4096. Берём запас под мультибайтные символы и разметку. */
    private static final int CHUNK_LIMIT = 4000;

    private final TaskExecutor taskExecutor;
    private final TelegramApi telegram;

    /**
     * Отправляет текстовое сообщение
     */
    public void sendMessage(long chatId, String text) {
        sendMessage(chatId, text, null);
    }

    /**
     * Отправляет текстовое сообщение с форматированием.
     * Длинный текст разбивается на части по границам строк и отправляется
     * последовательно (одной задачей, чтобы сохранить порядок).
     */
    public void sendMessage(long chatId, String text, String parseMode) {
        List<String> parts = splitMessage(text);

        taskExecutor.execute(() -> {
            for (String part : parts) {
                SendMessage msg = SendMessage.builder()
                        .chatId(String.valueOf(chatId))
                        .text(part)
                        .build();
                if (parseMode != null) msg.setParseMode(parseMode);

                try {
                    if (log.isDebugEnabled()) {
                        log.debug("OUT ▶ {}", part.replace("\n", "\\n"));
                    }
                    telegram.execute(msg);
                } catch (TelegramApiException e) {
                    log.error("Ошибка sendMessage", e);
                }
            }
        });
    }

    /**
     * Отправляет транскрипцию как документ
     */
    public void sendTranscript(long chatId, Path txt, InlineKeyboardMarkup keyboard) {
        taskExecutor.execute(() -> {
            try {
                SendDocument document = SendDocument.builder()
                        .chatId(String.valueOf(chatId))
                        .caption("✅ Ваша транскрипция")
                        .document(new InputFile(txt.toFile()))
                        .build();
                // Клавиатура необязательна: у старых расшифровок соседних
                // форматов нет, и предлагать их нечем
                if (keyboard != null) document.setReplyMarkup(keyboard);
                telegram.execute(document);
            } catch (TelegramApiException e) {
                log.error("Ошибка отправки файла", e);
            }
        });
    }

    /**
     * Отправляет произвольный файл в чат как документ.
     *
     * <p>Используется для отдачи скачанного видео прямо в Telegram. Если API
     * отклоняет отправку (чаще всего — превышен лимит 50 МБ), вызывается
     * {@code onFailure}, чтобы вызывающий код мог отправить ссылку.</p>
     */
    public void sendFile(long chatId, Path file, String caption, Runnable onFailure) {
        taskExecutor.execute(() -> {
            try {
                sendChatActionSync(chatId, "upload_document");
                telegram.execute(SendDocument.builder()
                        .chatId(String.valueOf(chatId))
                        .caption(caption)
                        .document(new InputFile(file.toFile()))
                        .build());
                log.info("Файл отправлен в чат: chatId={}, file={}", chatId, file.getFileName());
            } catch (Exception e) {
                log.error("Не удалось отправить файл в чат: chatId={}, file={}", chatId, file.getFileName(), e);
                if (onFailure != null) onFailure.run();
            }
        });
    }

    /** Синхронный chat action — вызывается уже внутри асинхронной задачи. */
    private void sendChatActionSync(long chatId, String action) {
        try {
            telegram.execute(SendChatAction.builder()
                    .chatId(String.valueOf(chatId))
                    .action(action)
                    .build());
        } catch (TelegramApiException e) {
            log.debug("Не удалось отправить chat action '{}': chatId={}", action, chatId, e);
        }
    }

    /**
     * Отправляет сообщение с инлайн клавиатурой.
     * Если текст длинный, ведущие части уходят обычными сообщениями, а клавиатура
     * прикрепляется к последней части.
     */
    public void sendMessageWithKeyboard(long chatId, String text, String parseMode, InlineKeyboardMarkup keyboard) {
        List<String> parts = splitMessage(text);

        taskExecutor.execute(() -> {
            for (int i = 0; i < parts.size(); i++) {
                SendMessage msg = SendMessage.builder()
                        .chatId(String.valueOf(chatId))
                        .text(parts.get(i))
                        .build();
                if (parseMode != null) msg.setParseMode(parseMode);
                if (i == parts.size() - 1) msg.setReplyMarkup(keyboard);

                try {
                    if (log.isDebugEnabled()) {
                        log.debug("OUT ▶ {}", parts.get(i).replace("\n", "\\n"));
                    }
                    telegram.execute(msg);
                } catch (TelegramApiException e) {
                    log.error("Ошибка sendMessageWithKeyboard", e);
                }
            }
        });
    }

    /**
     * Показывает действие пользователя (typing, upload_document и т. п.)
     */
    public void sendChatAction(long chatId, String action) {
        taskExecutor.execute(() -> {
            try {
                telegram.execute(SendChatAction.builder()
                        .chatId(String.valueOf(chatId))
                        .action(action)
                        .build());
            } catch (TelegramApiException e) {
                // Индикатор «печатает» некритичен: не срываем обработку, но след оставляем
                log.debug("Не удалось отправить chat action '{}': chatId={}", action, chatId, e);
            }
        });
    }

    /* ───────── helpers ───────── */

    /**
     * Экранирует текст для parse_mode=HTML.
     *
     * <p>Telegram отвергает всё сообщение с 400, если в HTML-разметке встретился
     * неэкранированный {@code <}, {@code >} или {@code &}. Имя пользователя,
     * имя файла и URL приходят снаружи и вполне могут их содержать — без
     * экранирования пользователь просто не получал сообщение, в том числе
     * ссылку на скачанный файл.</p>
     */
    public static String escapeHtml(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    /**
     * Делит текст на части не длиннее {@link #CHUNK_LIMIT}, по возможности
     * разрывая по последнему переводу строки в пределах лимита. Не разрывает
     * суррогатные пары (эмодзи).
     */
    private List<String> splitMessage(String text) {
        List<String> parts = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            parts.add("");
            return parts;
        }

        int i = 0;
        int len = text.length();
        while (i < len) {
            int end = Math.min(i + CHUNK_LIMIT, len);

            if (end < len) {
                // стараемся разорвать по переводу строки
                int nl = text.lastIndexOf('\n', end);
                if (nl > i) {
                    end = nl + 1;
                } else if (Character.isHighSurrogate(text.charAt(end - 1))) {
                    // не рвём эмодзи посередине
                    end--;
                }
            }

            parts.add(text.substring(i, end));
            i = end;
        }
        return parts;
    }
}

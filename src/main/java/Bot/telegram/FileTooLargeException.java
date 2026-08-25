package Bot.telegram;

/**
 * Файл не может быть скачан ботом из-за лимита Telegram Bot API.
 *
 * <p>Bot API отдаёт файлы размером не более
 * {@link TelegramFileDownloader#TELEGRAM_FILE_LIMIT_BYTES}. Всё, что больше,
 * пользователь загружает через веб-форму (/upload). Исключение отделяет этот
 * ожидаемый сценарий от настоящих сбоев скачивания.</p>
 */
import java.io.IOException;

public class FileTooLargeException extends IOException {

    public FileTooLargeException(String message) {
        super(message);
    }

    public FileTooLargeException(String message, Throwable cause) {
        super(message, cause);
    }
}

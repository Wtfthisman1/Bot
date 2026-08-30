package Bot.telegram;

/**
 * Скачивание файлов из Telegram по fileId.
 *
 * <p>Ответственность: получение filePath через Bot API, формирование URL и
 * сохранение контента в пользовательское хранилище. Связан с {@link StorageManager}
 * и {@link TelegramApi}. Основные методы: {@code downloadVoice},
 * {@code downloadAudio}, {@code downloadVideo}, {@code downloadDocument}.</p>
 */
import Bot.config.Profiles;
import Bot.owner.Owner;
import Bot.service.StorageManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.GetFile;
import org.telegram.telegrambots.meta.api.objects.File;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

@Profile(Profiles.HOME)
@Service
@RequiredArgsConstructor
@Slf4j
public class TelegramFileDownloader {

    /**
     * Максимальный размер файла, который бот способен скачать через Bot API.
     * Всё, что больше, принимается только через веб-форму (/upload).
     */
    public static final long TELEGRAM_FILE_LIMIT_BYTES = 20L * 1024 * 1024;

    private final StorageManager storageManager;
    private final TelegramApi telegram;

    @Value("${bot.key}")
    private String botToken;

    /**
     * Скачивает файл из Telegram по fileId
     */
    public Path downloadFile(String fileId, long chatId, String originalName) throws Exception {
        // Получаем информацию о файле
        GetFile getFile = new GetFile();
        getFile.setFileId(fileId);
        
        log.debug("Запрашиваю файл у Bot API: chatId={}, originalName='{}'", chatId, originalName);

        File file;
        try {
            file = execute(getFile);
        } catch (TelegramApiException e) {
            // Размер не всегда приходит в апдейте — тогда лимит вскрывается только здесь
            if (isTooBig(e)) {
                log.info("Bot API отказал по размеру файла: chatId={}, originalName='{}'",
                        chatId, originalName);
                throw new FileTooLargeException(
                        "Файл превышает лимит Telegram Bot API (20 МБ)", e);
            }
            log.error("Ошибка Bot API при получении файла: chatId={}, originalName='{}'",
                    chatId, originalName, e);
            throw e;
        }
        if (file == null) {
            log.error("Bot API вернул пустую информацию о файле: chatId={}", chatId);
            throw new IOException("Не удалось получить информацию о файле: " + fileId);
        }

        // Формируем URL для скачивания
        String fileUrl = "https://api.telegram.org/file/bot" + botToken + "/" + file.getFilePath();
        
        // Определяем расширение файла
        String extension = getFileExtension(file.getFilePath());
        if (extension == null) {
            extension = getExtensionFromName(originalName);
        }
        
        // Создаем временное имя файла
        String tempFileName = "telegram_" + UUID.randomUUID().toString().substring(0, 8) + extension;
        
        // Путь для сохранения
        Path downloadPath = storageManager.uploadedPath(Owner.telegram(chatId), tempFileName);
        Files.createDirectories(downloadPath.getParent());
        
        // Скачиваем файл.
        //
        // Ошибка перехватывается и пересобирается без адреса: в fileUrl сидит
        // токен бота, а JDK вставляет весь URL в текст исключения
        // («Server returned HTTP response code: 403 for URL: ...»). Дальше этот
        // текст уходит в logs/app.log вместе со стектрейсом — то есть токен,
        // дающий полную власть над ботом, ложился в файл при каждой обычной
        // неудаче вроде протухшей ссылки Bot API.
        try (InputStream in = new URL(fileUrl).openStream()) {
            Files.copy(in, downloadPath, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IOException("Не удалось скачать файл из Telegram: "
                    + file.getFilePath() + " (" + e.getClass().getSimpleName() + ")");
        }
        
        log.info("Скачан файл из Telegram: chatId={}, путь={}, размер={} байт",
                chatId, downloadPath, Files.size(downloadPath));
        return downloadPath;
    }

    /**
     * Скачивает голосовое сообщение
     */
    public Path downloadVoice(String fileId, long chatId) throws Exception {
        return downloadFile(fileId, chatId, "voice.ogg");
    }

    /**
     * Скачивает аудио файл
     */
    public Path downloadAudio(String fileId, long chatId, String originalName) throws Exception {
        return downloadFile(fileId, chatId, originalName);
    }

    /**
     * Скачивает видео файл
     */
    public Path downloadVideo(String fileId, long chatId, String originalName) throws Exception {
        return downloadFile(fileId, chatId, originalName);
    }

    /**
     * Скачивает документ
     */
    public Path downloadDocument(String fileId, long chatId, String originalName) throws Exception {
        return downloadFile(fileId, chatId, originalName);
    }

    /**
     * Получает расширение файла из пути
     */
    private String getFileExtension(String filePath) {
        if (filePath == null) return null;
        int lastDot = filePath.lastIndexOf('.');
        return lastDot > 0 ? filePath.substring(lastDot) : null;
    }

    /**
     * Получает расширение из имени файла
     */
    private String getExtensionFromName(String fileName) {
        if (fileName == null) return ".bin";
        int lastDot = fileName.lastIndexOf('.');
        return lastDot > 0 ? fileName.substring(lastDot) : ".bin";
    }

    /**
     * Выполняет запрос к Telegram API
     */
    private File execute(GetFile getFile) throws TelegramApiException {
        return telegram.execute(getFile);
    }

    /**
     * Отличает отказ по размеру от прочих ошибок Bot API.
     * Telegram отвечает 400 с описанием «file is too big».
     */
    private boolean isTooBig(TelegramApiException e) {
        String message = e.getMessage();
        return message != null && message.toLowerCase().contains("file is too big");
    }
}


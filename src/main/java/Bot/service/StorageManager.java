package Bot.service;

/**
 * Управление файловым хранилищем пользователей.
 *
 * <p>Ответственность: структура папок, генерация имён, пути для uploaded/
 * downloaded/transcripts. Используется загрузчиком, транскрипцией и
 * контроллером формы. Ключевые методы: {@code uploadedPath},
 * {@code downloadedPath}, {@code transcriptPath}, {@code getTranscriptsDir}.</p>
 *
 * <p>Каталог пользователя выбирается по {@link Owner#storageKey()}, а не по
 * chatId: у аккаунта сайта чата нет вовсе. Для чатов ключ — тот же chatId,
 * поэтому уже лежащие на диске каталоги остаются на своих местах.</p>
 */
import jakarta.annotation.PostConstruct;
import Bot.config.Profiles;
import Bot.owner.Owner;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;

@Profile(Profiles.HOME)
@Component
@Slf4j
public class StorageManager {

    @Value("${app.storage.base}")
    private String storageBase;
    private Path storageRoot;

    /**
     * Сколько байт разрешено занимать одному владельцу.
     *
     * <p>Квота считает расшифровки, а не гигабайты: три задачи в месяц — это
     * три файла, но каждый может быть на 2,5 ГБ, а форма принимает пять разом.
     * Плюс скачанные ролики, которые лимита не тратят вовсе. Без потолка любой
     * вошедший забивал диск домашней машины, ничего при этом не нарушая.</p>
     */
    @Value("${storage.max-bytes-per-owner:10737418240}")
    private long maxBytesPerOwner;

    private static final DateTimeFormatter DTF =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS");

    /* ---------------- init ---------------- */

    @PostConstruct
    void init() throws IOException {
        storageRoot = Path.of(storageBase).toAbsolutePath();
        Files.createDirectories(storageRoot);
    }

    /* ---------------- public API ---------------- */

    public Path userRoot(Owner owner) throws IOException {
        Path userPath = storageRoot.resolve(owner.storageKey());
        Files.createDirectories(userPath);
        return userPath;
    }

    public Path uploadedPath(Owner owner, String originalName) throws IOException {
        return ensureSubDir(owner, "uploaded")
                .resolve(fileName(originalName, null));
    }

    /**
     * Путь для скачиваемого медиа. Расширение задаёт вызывающий: аудиодорожка
     * приезжает .m4a, видео — .mp4, а Downloader.py ждёт точное имя файла.
     */
    public Path downloadedPath(Owner owner, String url, String extension) throws IOException {
        String title = videoTitle(url);
        return ensureSubDir(owner, "downloaded")
                .resolve(fileName(title, extension));
    }

    /**
     * Влезет ли ещё столько байт в каталог владельца.
     *
     * <p>Считаются только записи — {@code uploaded} и {@code downloaded};
     * расшифровки это текст, они не весят ничего и живут дольше файлов.</p>
     */
    public boolean hasRoomFor(Owner owner, long bytes) throws IOException {
        if (maxBytesPerOwner <= 0) {
            return true;
        }
        long used = usedBytes(owner);
        if (used + Math.max(0, bytes) <= maxBytesPerOwner) {
            return true;
        }
        log.info("Владелец {} упёрся в потолок хранилища: занято {} МБ из {} МБ",
                owner, used / 1048576, maxBytesPerOwner / 1048576);
        return false;
    }

    /**
     * Есть ли у владельца место вообще — когда размер будущего файла неизвестен.
     *
     * <p>Так спрашивает скачивание: сколько весит ролик, выяснится уже внутри
     * yt-dlp. Ошибку чтения каталога считаем «место есть»: не сумев посчитать
     * занятое, останавливать работу всем — плохой обмен.</p>
     */
    public boolean hasRoom(Owner owner) {
        try {
            return hasRoomFor(owner, 0);
        } catch (IOException e) {
            log.warn("Не удалось посчитать занятое место владельца {}", owner, e);
            return true;
        }
    }

    /** Сколько уже занято записями этого владельца. */
    public long usedBytes(Owner owner) throws IOException {
        long total = 0;
        for (String dirName : new String[]{"uploaded", "downloaded"}) {
            Path dir = userRoot(owner).resolve(dirName);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (java.util.stream.Stream<Path> files = Files.list(dir)) {
                total += files.filter(Files::isRegularFile).mapToLong(file -> {
                    try {
                        return Files.size(file);
                    } catch (IOException e) {
                        return 0;
                    }
                }).sum();
            }
        }
        return total;
    }

    /** Сколько всего можно занять — для текста отказа. */
    public long maxBytesPerOwner() {
        return maxBytesPerOwner;
    }

    public Path transcriptPath(Owner owner, String baseName) throws IOException {
        String name = sanitize(baseName).replaceFirst("\\.[^.]+$", "");
        return ensureSubDir(owner, "transcripts")
                .resolve(name+ ".txt");
    }

    public Path getStorageRoot() {
        return storageRoot;
    }

    public Path getTranscriptsDir(Owner owner) throws IOException {
        return ensureSubDir(owner, "transcripts");
    }

    /* ---------------- helpers ---------------- */



    private String videoTitle(String url) throws IOException {
        // «--» обязателен: без него ссылка, начинающаяся с дефиса, разбирается
        // yt-dlp как опция, а не как адрес. Проверено: строка «--version» в этой
        // позиции печатает версию вместо отказа, а рядом в справке живут
        // --exec, --config-location и --paths
        ProcessBuilder pb = new ProcessBuilder("yt-dlp",
                "-e",
                "--no-warnings", 
                "--ignore-errors", 
                "--no-playlist",
                "--quiet",
                "--",
                url)
                .redirectError(ProcessBuilder.Redirect.DISCARD);  // Игнорируем stderr полностью
        
        Process p = pb.start();
        
        try {
            // Ждем завершения процесса с таймаутом
            boolean completed = p.waitFor(15, TimeUnit.SECONDS);
            if (!completed) {
                log.warn("Таймаут получения названия видео для URL: {}", url);
                p.destroyForcibly();
                return "video_" + System.currentTimeMillis();
            }
            
            // Проверяем код выхода
            int exitCode = p.exitValue();
            if (exitCode != 0) {
                log.warn("yt-dlp завершился с ошибкой {} для URL: {}", exitCode, url);
                return "video_" + System.currentTimeMillis();
            }
            
            // Читаем только stdout и фильтруем результат
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    // Фильтруем строки - убираем предупреждения и ошибки
                    if (!isWarningOrError(line)) {
                        output.append(line).append('\n');
                    }
                }
            }
            
            // Обрабатываем результат
            String result = output.toString().trim();
            if (result.isEmpty()) {
                log.warn("Пустое название видео для URL: {}", url);
                return "video_" + System.currentTimeMillis();
            }
            
            // Берем первую непустую строку (название)
            String[] lines = result.split("\n");
            String title = null;
            for (String line : lines) {
                line = line.trim();
                if (!line.isEmpty() && !isWarningOrError(line)) {
                    title = line;
                    break;
                }
            }
            
            if (title == null || title.isEmpty()) {
                log.warn("Не найдено название видео для URL: {}", url);
                return "video_" + System.currentTimeMillis();
            }
            
            // Ограничиваем длину названия
            if (title.length() > 100) {
                title = title.substring(0, 97) + "...";
            }
            
            log.debug("Получено название видео: '{}' для URL: {}", title, url);
            return title;
            
        } catch (InterruptedException e) {
            log.warn("Прерывание при получении названия видео для URL: {}", url, e);
            Thread.currentThread().interrupt();
            return "video_" + System.currentTimeMillis();
        } catch (Exception e) {
            log.warn("Ошибка получения названия видео для URL: {}", url, e);
            return "video_" + System.currentTimeMillis();
        } finally {
            // Убеждаемся, что процесс завершен
            if (p.isAlive()) {
                p.destroyForcibly();
            }
        }
    }
    
    /**
     * Проверяет, является ли строка предупреждением или ошибкой
     */
    private boolean isWarningOrError(String line) {
        if (line == null || line.trim().isEmpty()) {
            return true;
        }
        
        String lowerLine = line.toLowerCase();
        
        // Фильтруем предупреждения и ошибки
        return lowerLine.contains("warning") ||
               lowerLine.contains("error") ||
               lowerLine.contains("failed") ||
               lowerLine.contains("unable") ||
               lowerLine.contains("invalid") ||
               lowerLine.contains("unsupported") ||
               lowerLine.contains("timeout") ||
               lowerLine.contains("network") ||
               lowerLine.contains("connection") ||
               lowerLine.startsWith("[") && lowerLine.contains("]") ||
               lowerLine.startsWith("error:") ||
               lowerLine.startsWith("warning:");
    }



    private String fileName(String base, String ext) {
        String ts = DTF.format(LocalDateTime.now());
        String safe = sanitize(base);
        return safe + "_" + ts  + (ext != null ? ext : "");
    }

    private String sanitize(String name) {
        String s = name.replaceAll("[^\\p{L}\\p{N}._-]", "_");
        s = s.replaceAll("_+", "_");
        return s.replaceAll("^_+|_+$", "");
    }


    private Path ensureSubDir(Owner owner, String dirName) throws IOException {
        Path dir = userRoot(owner).resolve(dirName);
        Files.createDirectories(dir);
        return dir;
    }

}

package Bot.download;

/**
 * Исполнитель загрузки видео/аудио через Python (yt-dlp).
 *
 * <p>Ответственность: запускает скрипт скачивания, пишет файл в хранилище,
 * анализирует выход yt-dlp и формирует понятные ошибки. Связан с
 * {@link StorageManager}. Основной метод: {@code download}.</p>
 */
import Bot.config.Profiles;
import Bot.owner.Owner;
import Bot.processing.MediaKind;
import Bot.service.StorageManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import org.slf4j.MDC;
import java.util.concurrent.TimeUnit;

@Profile(Profiles.HOME)
@Service
@Slf4j
@RequiredArgsConstructor
public class DownloaderExecutor {

    // 1. Путь к интерпретатору Python (виртуальное окружение)
    @Value("${python.executable.path}")
    private String pythonPath;

    // 2. Путь к вашему Python-скрипту
    @Value("${downloader.script}")
    private String scriptPath;

    /**
     * Каталог с JS-рантаймом (deno). Свежий yt-dlp без него не может извлечь
     * форматы YouTube и падает с 403. У systemd/Docker PATH урезан, поэтому
     * каталог добавляется в PATH дочернего процесса явно.
     */
    @Value("${downloader.js-runtime-dir:${user.home}/.deno/bin}")
    private String jsRuntimeDir;

    /** максимум ждём 30 минут на особо большие ролики */
    private static final Duration PROCESS_TIMEOUT = Duration.ofMinutes(30);

    private final StorageManager storageManager;

    /**
     * Качает медиа по url в {owner}/downloaded/{slug}_{timestamp}{ext}
     *
     * @param media что тянуть: только звук (для транскрипции) или видео
     * @return полный {@link Path} к загруженному файлу
     */
    public Path download(Owner owner, String url, MediaKind media)
            throws IOException, InterruptedException {

        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(media, "media");
        if (url.isBlank())
            throw new IllegalArgumentException("URL is blank");

        /* 1. путь назначения */
        Path dst = storageManager.downloadedPath(owner, url, media.extension());
        Files.createDirectories(dst.getParent());

        /* 2. где лежит python-скрипт (распаковываем из JAR или берем с диска) */
        String script = resolveScriptPath(scriptPath);

        /* 3. запускаем: <python> <скрипт> <url> <outDir> <fileName> <mode> */
        ProcessBuilder pb = new ProcessBuilder(
                pythonPath, // Используем Python из application.properties
                script,     // Используем скрипт из application.properties
                url,
                dst.getParent().toString(),
                dst.getFileName().toString(),
                media.scriptMode()
        );
        // хотим видеть stderr отдельно
        pb.redirectErrorStream(false);

        addJsRuntimeToPath(pb);

        Process proc = pb.start();
        log.info("Скачивание запущено: media={}, pid={}, файл={}",
                media, proc.pid(), dst.getFileName());


        /* читаем STDOUT и STDERR параллельно */
        StringBuilder outBuf = new StringBuilder();
        StringBuilder errBuf = new StringBuilder();

        Thread tOut = pipeStream(proc.getInputStream(), ln -> {
            // [progress] уже троттлится скриптом до одной строки в 5 секунд —
            // его видно в консоли; остальное оседает в логе задачи
            if (ln.startsWith("[progress]") || ln.startsWith("[Downloader.py]")) {
                log.info("{}", ln);
            } else {
                log.debug("[YT-DLP] {}", ln);
            }
            outBuf.append(ln).append('\n');
        });
        // ВАЖНО: stderr пишется в DEBUG, а не в ERROR. yt-dlp сыплет туда
        // сообщения о переподключениях, которые сам же и переигрывает: одна
        // загрузка с обрывами сети выдала админу восемь алертов, хотя
        // завершилась успешно. Настоящая ошибка — это ненулевой код возврата,
        // он ниже разбирается в analyzeYtDlpError и логируется один раз.
        Thread tErr = pipeStream(proc.getErrorStream(), ln -> {
            log.debug("[YT-DLP:err] {}", ln);
            errBuf.append(ln).append('\n');
        });

        boolean finished = proc.waitFor(PROCESS_TIMEOUT.toMinutes(), TimeUnit.MINUTES);
        if (!finished) {
            proc.destroyForcibly();
            throw new RuntimeException("YT-DLP timeout > " + PROCESS_TIMEOUT);
        }

        tOut.join();
        tErr.join();

        int exit = proc.exitValue();
        if (exit != 0) {
            String errorOutput = errBuf.toString();
            String errorMessage = analyzeYtDlpError(exit, errorOutput, url);
            log.error("Скачивание не удалось: код={}, url={}", exit, url);
            throw new RuntimeException(errorMessage);
        }

        if (!Files.exists(dst))
            throw new IOException("Файл не создан: " + dst);

        return dst;
    }

    /**
     * Анализирует ошибки yt-dlp и возвращает понятное сообщение
     */
    private String analyzeYtDlpError(int exitCode, String errorOutput, String url) {
        String lowerError = errorOutput.toLowerCase();
        
        if (lowerError.contains("video unavailable") || lowerError.contains("private")) {
            return String.format("❌ Видео недоступно или приватное: %s", url);
        }
        
        if (lowerError.contains("unsupported url") || lowerError.contains("no video id")) {
            return String.format("❌ Неподдерживаемый URL: %s", url);
        }
        
        if (lowerError.contains("sign in") || lowerError.contains("login")) {
            return String.format("❌ Требуется авторизация для доступа к видео: %s", url);
        }
        
        if (lowerError.contains("quota") || lowerError.contains("limit")) {
            return String.format("❌ Превышен лимит запросов для: %s", url);
        }
        
        if (lowerError.contains("network") || lowerError.contains("connection")) {
            return String.format("❌ Ошибка сети при загрузке: %s", url);
        }
        
        if (lowerError.contains("age restricted") || lowerError.contains("age_restricted")) {
            return String.format("❌ Видео с возрастными ограничениями: %s", url);
        }
        
        if (lowerError.contains("copyright") || lowerError.contains("blocked")) {
            return String.format("❌ Видео заблокировано из-за авторских прав: %s", url);
        }
        
        // Общая ошибка
        return String.format("❌ Ошибка загрузки видео (код %d): %s\n\nДетали:\n%s", 
            exitCode, url, errorOutput);
    }

    /* ─────────── helpers ─────────── */

    /** Добавляет каталог с deno в PATH дочернего процесса, если он существует. */
    private void addJsRuntimeToPath(ProcessBuilder pb) {
        if (jsRuntimeDir == null || jsRuntimeDir.isBlank()) return;

        Path dir = Path.of(jsRuntimeDir);
        if (!Files.isDirectory(dir)) {
            log.warn("JS-рантайм не найден в {} — скачивание с YouTube, скорее всего, упадёт с 403. "
                    + "Установите deno или задайте downloader.js-runtime-dir", jsRuntimeDir);
            return;
        }

        Map<String, String> env = pb.environment();
        String path = env.get("PATH");
        env.put("PATH", path == null || path.isBlank()
                ? dir.toString()
                : dir + File.pathSeparator + path);
    }

    /** Находит путь до скрипта с учётом fat-jar и classpath:-синтаксиса. */
    private String resolveScriptPath(String raw) throws IOException {
        if (raw.startsWith("classpath:")) {
            String res = raw.substring("classpath:".length());
            URL url = Objects.requireNonNull(
                    getClass().getClassLoader().getResource(res),
                    "Script not found in classpath: " + raw);
            try {
                // Проверяем, является ли URI иерархическим
                if ("jar".equals(url.getProtocol())) {
                    // Для JAR файлов извлекаем во временную директорию
                    Path tempDir = Files.createTempDirectory("python-scripts");
                    Path scriptFile = tempDir.resolve(new File(res).getName());
                    
                    try (InputStream in = url.openStream();
                         OutputStream out = Files.newOutputStream(scriptFile)) {
                        in.transferTo(out);
                    }
                    
                    // Делаем файл исполняемым
                    scriptFile.toFile().setExecutable(true);
                    return scriptFile.toAbsolutePath().toString();
                } else {
                    return new File(url.toURI()).getAbsolutePath();
                }
            } catch (Exception e) {
                throw new IOException("Не удалось извлечь скрипт из classpath", e);
            }
        }
        return raw;
    }

    /**
     * Запускает поток-ридер для вывода процесса.
     *
     * <p>MDC копируется в дочерний поток вручную: начиная с logback 1.3 контекст
     * не наследуется, и все строки yt-dlp писались как {@code [chat=— job=—]}.
     * При двух параллельных загрузках их вывод был неразличим в логе.</p>
     */
    private Thread pipeStream(java.io.InputStream in, java.util.function.Consumer<String> sink) {
        Map<String, String> parentMdc = MDC.getCopyOfContextMap();

        Thread t = new Thread(() -> {
            if (parentMdc != null) MDC.setContextMap(parentMdc);
            try (BufferedReader br = new BufferedReader(new InputStreamReader(in))) {
                String ln;
                while ((ln = br.readLine()) != null) sink.accept(ln);
            } catch (IOException e) {
                log.warn("Обрыв чтения вывода процесса загрузки", e);
            } finally {
                MDC.clear();
            }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }
}

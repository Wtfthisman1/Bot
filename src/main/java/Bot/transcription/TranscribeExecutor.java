package Bot.transcription;

/**
 * Исполнитель транскрипции через Python-скрипт (whisper-ctranslate2 + VAD).
 *
 * <p>Ответственность: запуск внешнего процесса для преобразования аудио/видео
 * в текст, контроль таймаутов, проброс настроек распознавания в процесс и разбор
 * ошибок. Связан со {@link StorageManager}. Основной метод: {@code run} —
 * возвращает путь к .txt с транскриптом.</p>
 */
import Bot.processing.GpuLock;
import Bot.service.StorageManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.MDC;

@Service
@RequiredArgsConstructor
@Slf4j
public class TranscribeExecutor {

    /** Префикс строки stderr, которой Transcribe.py сообщает определённый язык. */
    private static final String DETECTED_LANGUAGE_MARKER = "[transcribe] detected-language=";

    private final GpuLock gpuLock;

    /** whisper.script = classpath:pythonScript/Transcribe.py */
    // 1. Путь к интерпретатору Python (из виртуального окружения)
    @Value("${python.executable.path}")
    private String pythonPath;

    // 2. Путь к самому скрипту транскрипции
    @Value("${whisper.script}")
    private String scriptPath;

    /**
     * Максимальное время ожидания транскрипции в минутах.
     * Транскрипция длинного видео на CPU может идти часами, поэтому по умолчанию
     * 300 мин (5 ч). Настраивается свойством {@code whisper.timeout-minutes}.
     */
    @Value("${whisper.timeout-minutes:300}")
    private long timeoutMinutes;

    /* ── Настройки распознавания (пробрасываются в Python как env) ── */
    @Value("${whisper.model:large-v3}")
    private String model;
    @Value("${whisper.compute-type:int8}")
    private String computeType;
    @Value("${whisper.device:cpu}")
    private String device;
    @Value("${whisper.threads:6}")
    private int threads;
    /** {@code auto} — whisper определяет язык сам; иначе язык навязывается. */
    @Value("${whisper.language:auto}")
    private String language;
    @Value("${whisper.clean-audio:false}")
    private boolean cleanAudio;
    @Value("${whisper.initial-prompt:}")
    private String initialPrompt;
    @Value("${whisper.ct-binary:whisper-ctranslate2}")
    private String ctBinary;

    /** Дольше этой паузы без вывода — уже повод для WARN, а не для «всё идёт». */
    private static final long SILENCE_WARN_SECONDS = 180;

    /** Как часто писать «ещё работаю» пока Whisper молотит. */
    private static final long HEARTBEAT_SECONDS = 30;

    /** Строка распознанного сегмента: [00:12.340 --> 00:15.100] текст. */
    private static final java.util.regex.Pattern SEGMENT_LINE =
            java.util.regex.Pattern.compile("^\\[\\d+:\\d+\\.\\d+\\s*-->");

    private final StorageManager storageManager;

    /**
     * Запускает whisper-ctranslate2 и возвращает путь к .txt-транскрипту
     */
    public Path run(long chatId, Path video) throws IOException, InterruptedException {

        Objects.requireNonNull(video, "video");
        if (!Files.exists(video))
            throw new IOException("Видеофайл не найден: " + video);

        long startedAt = System.currentTimeMillis();
        log.info("Начинаю транскрипцию: chatId={}, файл={}, размер={} МБ",
                chatId, video.getFileName(), Files.size(video) / 1048576);

        /* 1. где лежит скрипт */
        String script = resolveScript(scriptPath);
        log.debug("Скрипт транскрипции: {}", script);

        /* 2. куда писать результат */
        String baseName = video.getFileName().toString()
                .replaceFirst("\\.[^.]+$", "");     // без расширения
        Path   txtFile  = storageManager.transcriptPath(chatId, baseName);

        Files.createDirectories(txtFile.getParent());

        /* 3. запуск: python Transcribe.py <video> <out.txt> */
        log.debug("Запускаю процесс: python3 {} {} {}", script, video, txtFile);

        ProcessBuilder pb = new ProcessBuilder(
                pythonPath, script,
                video.toString(),
                txtFile.toString())
                .redirectErrorStream(false);        // хотим stderr отдельно

        // Проброс настроек распознавания в дочерний процесс.
        // Значения из .env/properties живут в Spring Environment, а не в OS env,
        // поэтому Python увидит их только если положить сюда явно.
        Map<String, String> env = pb.environment();
        env.put("WHISPER_MODEL", model);
        env.put("WHISPER_COMPUTE_TYPE", computeType);
        env.put("WHISPER_DEVICE", device);
        env.put("WHISPER_THREADS", String.valueOf(threads));
        env.put("WHISPER_LANGUAGE", language);
        env.put("WHISPER_CLEAN_AUDIO", String.valueOf(cleanAudio));
        env.put("WHISPER_INITIAL_PROMPT", initialPrompt != null ? initialPrompt : "");
        env.put("WHISPER_CT_BINARY", ctBinary);

        // Видеокарта занимается ровно на время работы Whisper. Подготовка выше и
        // проверки файла ниже её не трогают — держать пропуск дольше значило бы
        // зря задерживать соседние задачи.
        gpuLock.acquire("транскрипция " + video.getFileName());
        try {

        Process proc = pb.start();
        log.info("Whisper запущен: pid={}, модель={}, устройство={}, потоков={}, язык={}",
                proc.pid(), model, device, threads, language);

        StringBuilder errBuf = new StringBuilder();

        // Признаки жизни для пульса: когда пришла последняя строка и какая.
        // Без них «идёт транскрипция» неотличимо от «процесс повис».
        AtomicLong lastOutputAt = new AtomicLong(System.currentTimeMillis());
        AtomicReference<String> lastLine = new AtomicReference<>("");
        AtomicLong segmentCount = new AtomicLong();
        AtomicReference<String> detectedLanguage = new AtomicReference<>("");

        Thread tOut = streamToLog(proc.getInputStream(), ln -> {
            lastOutputAt.set(System.currentTimeMillis());
            lastLine.set(ln);
            // Строки вида [00:12.340 --> 00:15.100] текст — это распознанные сегменты.
            // Первый из них означает, что модель загрузилась и распознавание пошло
            if (SEGMENT_LINE.matcher(ln).find() && segmentCount.getAndIncrement() == 0) {
                log.info("Whisper начал распознавание — первый сегмент получен");
            }
            log.debug("[WHISPER] {}", ln);
        });
        Thread tErr = streamToLog(proc.getErrorStream(), ln -> {
            lastOutputAt.set(System.currentTimeMillis());
            lastLine.set(ln);

            // DEBUG, а не ERROR: строки со словом «error» попадаются и в штатном
            // выводе, а алерт админу должен уходить один раз — по коду возврата
            // Скрипт печатает сюда язык, который whisper определил сам. Это не
            // отладка: по языку выбирается модель для последующей обработки текста.
            if (ln.startsWith(DETECTED_LANGUAGE_MARKER)) {
                detectedLanguage.set(ln.substring(DETECTED_LANGUAGE_MARKER.length()).trim());
            }
            log.debug("[WHISPER:err] {}", ln);
            errBuf.append(ln).append('\n');
        });

        log.info("Ожидаю завершения Whisper (таймаут: {} мин), пульс каждые {} с",
                timeoutMinutes, HEARTBEAT_SECONDS);
        boolean ok = awaitWithHeartbeat(proc, lastOutputAt, lastLine, segmentCount);
        if (!ok) {
            log.error("Whisper не завершился за {} минут, принудительно завершаю", timeoutMinutes);
            proc.destroyForcibly();
            throw new RuntimeException("Whisper timeout > " + timeoutMinutes + " мин");
        }
        log.info("Whisper завершился с кодом {}: сегментов={}, язык={}, заняло {}",
                proc.exitValue(), segmentCount.get(),
                detectedLanguage.get().isEmpty() ? "не определён" : detectedLanguage.get(),
                humanDuration(System.currentTimeMillis() - startedAt));

        tOut.join();  tErr.join();

        if (proc.exitValue() != 0) {
            String errorDetails = errBuf.toString();
            log.error("Транскрипция не удалась: код={}, файл={}",
                    proc.exitValue(), video.getFileName());
            String errorMessage = "Whisper exited " + proc.exitValue();

            // Анализируем ошибку
            if (errorDetails.toLowerCase().contains("empty transcription")) {
                throw new RuntimeException("Whisper не смог распознать речь в аудио. Возможные причины:\n" +
                    "• Слишком короткое аудио (менее 3 секунд)\n" +
                    "• Отсутствует речь в аудио\n" +
                    "• Плохое качество записи\n" +
                    "• Неподдерживаемый язык\n\n" +
                    "Детали: " + errorDetails);
            }

            if (errorDetails.toLowerCase().contains("model")) {
                throw new RuntimeException("Ошибка загрузки модели Whisper:\n" + errorDetails);
            }

            throw new RuntimeException(errorMessage + "\n" + errorDetails);
        }

        } finally {
            gpuLock.release();
        }

        if (!Files.exists(txtFile)) {
            throw new IOException("Транскрипция не создана: " + txtFile);
        }

        // Проверяем размер файла и содержимое
        long fileSize = Files.size(txtFile);
        if (fileSize == 0) {
            throw new RuntimeException("Транскрипция пустая. Whisper не смог распознать речь в аудио.");
        }

        // Проверяем, что файл содержит текст, а не только пробелы
        String content = Files.readString(txtFile).trim();
        if (content.isEmpty()) {
            throw new RuntimeException("Транскрипция пустая. Whisper не смог распознать речь в аудио.");
        }

        return txtFile;
    }

    /* ───────── helpers ───────── */

    /**
     * Ждёт процесс, периодически подтверждая в логе, что работа идёт.
     *
     * <p>Транскрипция часового ролика занимает минуты и при уровне INFO не
     * писала в лог ничего между стартом и результатом — отличить «считает» от
     * «повис» было невозможно. Пульс печатает прошедшее время, число уже
     * распознанных сегментов и давность последнего вывода; если вывода нет
     * дольше {@link #SILENCE_WARN_SECONDS}, это уже WARN.</p>
     *
     * @return false, если истёк общий таймаут
     */
    private boolean awaitWithHeartbeat(Process proc,
                                       AtomicLong lastOutputAt,
                                       AtomicReference<String> lastLine,
                                       AtomicLong segmentCount) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMinutes * 60_000;
        long startedAt = System.currentTimeMillis();

        while (System.currentTimeMillis() < deadline) {
            if (proc.waitFor(HEARTBEAT_SECONDS, TimeUnit.SECONDS)) {
                return true;
            }

            long elapsed = System.currentTimeMillis() - startedAt;
            long silence = (System.currentTimeMillis() - lastOutputAt.get()) / 1000;
            long segments = segmentCount.get();

            if (silence >= SILENCE_WARN_SECONDS) {
                log.warn("Whisper молчит {} с: работает {}, сегментов={}. "
                                + "Возможна загрузка модели или зависание",
                        silence, humanDuration(elapsed), segments);
            } else if (segments > 0) {
                log.info("Whisper работает: {}, распознано сегментов={}, последний вывод {} с назад",
                        humanDuration(elapsed), segments, silence);
            } else {
                log.info("Whisper работает: {}, распознавание ещё не началось "
                        + "(загружается модель {})", humanDuration(elapsed), model);
            }
            log.debug("Последняя строка Whisper: {}", lastLine.get());
        }
        return proc.waitFor(0, TimeUnit.SECONDS);
    }

    private static String humanDuration(long millis) {
        long totalSeconds = millis / 1000;
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return minutes > 0 ? minutes + " мин " + seconds + " с" : seconds + " с";
    }

    /** Возвращает абсолютный путь к скрипту, корректно для classpath и fat-jar */
    private String resolveScript(String raw) throws IOException {
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
                throw new IOException("Не удалось извлечь скрипт", e);
            }
        }
        return raw;
    }

    /**
     * Перекачивает stream post-line в лог/коллектор.
     *
     * <p>MDC копируется вручную: с logback 1.3 дочерние потоки его не наследуют,
     * из-за чего весь вывод Whisper писался как {@code [chat=— job=—]} и не
     * попадал в пофайловый лог задачи.</p>
     */
    private Thread streamToLog(java.io.InputStream in,
                               java.util.function.Consumer<String> sink) {
        Map<String, String> parentMdc = MDC.getCopyOfContextMap();

        Thread t = new Thread(() -> {
            if (parentMdc != null) MDC.setContextMap(parentMdc);
            try (BufferedReader br = new BufferedReader(new InputStreamReader(in))) {
                String ln;
                while ((ln = br.readLine()) != null) sink.accept(ln);
            } catch (IOException e) {
                log.warn("Обрыв чтения вывода процесса транскрипции", e);
            } finally {
                MDC.clear();
            }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }
}

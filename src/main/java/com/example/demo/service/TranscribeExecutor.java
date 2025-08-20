package com.example.demo.service;

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
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class TranscribeExecutor {

    /** whisper.script = classpath:pythonScript/Transcribe.py */
    @Value("${whisper.script}")
    private String scriptPath;

    /** сколько максимум ждём трансформацию (15 мин) */
    private static final Duration TIMEOUT = Duration.ofHours(5);

    private final StorageManager storageManager;

    /**
     * Запускает Faster-Whisper и возвращает путь к .txt-транскрипту
     */
    public Path run(long chatId, Path video) throws IOException, InterruptedException {

        Objects.requireNonNull(video, "video");
        if (!Files.exists(video))
            throw new IOException("Видеофайл не найден: " + video);

        log.info("Начинаю транскрипцию: chatId={}, video={}", chatId, video);

        /* 1. где лежит скрипт */
        String script = resolveScript(scriptPath);
        log.info("Скрипт транскрипции: {}", script);

        /* 2. куда писать результат */
        String baseName = video.getFileName().toString()
                .replaceFirst("\\.[^.]+$", "");     // без расширения
        Path   txtFile  = storageManager.transcriptPath(chatId, baseName);

        Files.createDirectories(txtFile.getParent());

        /* 3. запуск: python Transcribe.py <video> <out.txt> */
        log.info("Запускаю процесс: python3 {} {} {}", script, video.toString(), txtFile.toString());
        
        ProcessBuilder pb = new ProcessBuilder(
                "python3", script,
                video.toString(),
                txtFile.toString())
                .redirectErrorStream(false);        // хотим stderr отдельно

        Process proc = pb.start();
        log.info("Процесс запущен, PID: {}", proc.pid());

        StringBuilder errBuf = new StringBuilder();

        Thread tOut = streamToLog(proc.getInputStream(), ln -> log.info ("[WHISPER] {}", ln));
        Thread tErr = streamToLog(proc.getErrorStream(), ln -> {
            String lower = ln.toLowerCase();
            boolean isErr = lower.contains("error")
                    || lower.contains("failed")
                    || lower.contains("invalid")
                    || lower.contains("unable");

            if (isErr)  log.error("[WHISPER] {}", ln);
            else        log.debug("[WHISPER] {}", ln);

            errBuf.append(ln).append('\n');
        });

        log.info("Ожидаю завершения процесса (таймаут: {} минут)...", TIMEOUT.toMinutes());
        boolean ok = proc.waitFor(TIMEOUT.toMinutes(), TimeUnit.MINUTES);
        if (!ok) {
            log.error("Процесс не завершился за {} минут, принудительно завершаю", TIMEOUT.toMinutes());
            proc.destroyForcibly();
            throw new RuntimeException("Faster-Whisper timeout > " + TIMEOUT);
        }
        log.info("Процесс завершился с кодом: {}", proc.exitValue());

        tOut.join();  tErr.join();

        if (proc.exitValue() != 0) {
            String errorDetails = errBuf.toString();
            String errorMessage = "Faster-Whisper exited " + proc.exitValue();
            
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

    /** Перекачивает stream post-line в лог/коллектор */
    private Thread streamToLog(java.io.InputStream in,
                               java.util.function.Consumer<String> sink) {
        Thread t = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(in))) {
                String ln;
                while ((ln = br.readLine()) != null) sink.accept(ln);
            } catch (IOException ignore) {}
        });
        t.setDaemon(true);
        t.start();
        return t;
    }
}

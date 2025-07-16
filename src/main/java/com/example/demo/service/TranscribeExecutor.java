package com.example.demo.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
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

        /* 1. где лежит скрипт */
        String script = resolveScript(scriptPath);

        /* 2. куда писать результат */
        String baseName = video.getFileName().toString()
                .replaceFirst("\\.[^.]+$", "");     // без расширения
        Path   txtFile  = storageManager.transcriptPath(chatId, baseName);

        Files.createDirectories(txtFile.getParent());

        /* 3. запуск: python Transcribe.py <video> <out.txt> */
        ProcessBuilder pb = new ProcessBuilder(
                "python3", script,
                video.toString(),
                txtFile.toString())
                .redirectErrorStream(false);        // хотим stderr отдельно

        Process proc = pb.start();

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

        boolean ok = proc.waitFor(TIMEOUT.toMinutes(), TimeUnit.MINUTES);
        if (!ok) {
            proc.destroyForcibly();
            throw new RuntimeException("Faster-Whisper timeout > " + TIMEOUT);
        }

        tOut.join();  tErr.join();

        if (proc.exitValue() != 0)
            throw new RuntimeException("Faster-Whisper exited " + proc.exitValue() +
                    "\n" + errBuf);

        if (!Files.exists(txtFile) || Files.size(txtFile) == 0)
            throw new IOException("Транскрипция не создана: " + txtFile);

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
                return new File(url.toURI()).getAbsolutePath();
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

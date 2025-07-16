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
@Slf4j
@RequiredArgsConstructor
public class DownloaderExecutor {

    /** путь к python-скрипту; напр.  downloader.script=classpath:pythonScript/Downloader.py */
    @Value("${downloader.script}")
    private String scriptPath;

    /** максимум ждём 30 минут на особо большие ролики */
    private static final Duration PROCESS_TIMEOUT = Duration.ofMinutes(30);

    private final StorageManager storageManager;

    /**
     * Качает видео по url в chatId/downloaded/{timestamp}_{slug}.mp4
     *
     * @return полный {@link Path} к загруженному файлу
     */
    public Path download(long chatId, String url)
            throws IOException, InterruptedException {

        Objects.requireNonNull(url, "url");
        if (url.isBlank())
            throw new IllegalArgumentException("URL is blank");

        /* 1. путь назначения */
        Path dst = storageManager.downloadedPath(chatId, url);
        Files.createDirectories(dst.getParent());

        /* 2. где лежит python-скрипт */
        String script = resolveScriptPath(scriptPath);

        /* 3. запускаем: python Downloader.py <url> <outDir> <fileName> */
        ProcessBuilder pb = new ProcessBuilder(
                "python3", script,
                url,
                dst.getParent().toString(),
                dst.getFileName().toString()
        );
        // хотим видеть stderr отдельно
        pb.redirectErrorStream(false);

        Process proc = pb.start();

        /* читаем STDOUT и STDERR параллельно */
        StringBuilder outBuf = new StringBuilder();
        StringBuilder errBuf = new StringBuilder();

        Thread tOut = pipeStream(proc.getInputStream(), ln -> {
            log.info("[YT-DLP] {}", ln);
            outBuf.append(ln).append('\n');
        });
        Thread tErr = pipeStream(proc.getErrorStream(), ln -> {
            log.error("[YT-DLP] {}", ln);
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
        if (exit != 0)
            throw new RuntimeException("YT-DLP exited " + exit + "\n" + errBuf);

        if (!Files.exists(dst))
            throw new IOException("Файл не создан: " + dst);

        return dst;
    }

    /* ─────────── helpers ─────────── */

    /** Находит путь до скрипта с учётом fat-jar и classpath:-синтаксиса. */
    private String resolveScriptPath(String raw) throws IOException {
        if (raw.startsWith("classpath:")) {
            String res = raw.substring("classpath:".length());
            URL url = Objects.requireNonNull(
                    getClass().getClassLoader().getResource(res),
                    "Script not found in classpath: " + raw);
            try {
                return new File(url.toURI()).getAbsolutePath();
            } catch (Exception e) {
                throw new IOException("Не удалось извлечь скрипт из classpath", e);
            }
        }
        return raw;
    }

    /** Запускает поток-ридер для вывода процесса. */
    private Thread pipeStream(java.io.InputStream in, java.util.function.Consumer<String> sink) {
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

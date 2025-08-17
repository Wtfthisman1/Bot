package com.example.demo.queue;

import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;

public record ProcessingJob(
        String id,
        long chatId,
        Path filePath,    // null => ещё не скачано
        String url,       // null => локальный файл
        State state,
        String downloadId // null => обычная задача транскрибирования
) {
    public enum State { NEW, DOWNLOADED }

    /* приватный canonical-ctor с проверками */
    public ProcessingJob {
        if (state == State.NEW && url == null)
            throw new IllegalArgumentException("NEW job must have url");
        if (state == State.DOWNLOADED && filePath == null)
            throw new IllegalArgumentException("DOWNLOADED job must have filePath");
    }

    public static ProcessingJob newLink(long chatId, String url) {
        Objects.requireNonNull(url, "url");
        return new ProcessingJob(UUID.randomUUID().toString(),
                chatId, null, url, State.NEW, null);
    }

    public static ProcessingJob newFile(long chatId, Path file) {
        Objects.requireNonNull(file, "file");
        return new ProcessingJob(UUID.randomUUID().toString(),
                chatId, file, null, State.DOWNLOADED, null);
    }

    public static ProcessingJob newDownload(long chatId, String url, String downloadId) {
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(downloadId, "downloadId");
        return new ProcessingJob(UUID.randomUUID().toString(),
                chatId, null, url, State.NEW, downloadId);
    }

    public ProcessingJob withFile(Path file) {
        Objects.requireNonNull(file, "file");
        return new ProcessingJob(id, chatId, file, null, State.DOWNLOADED, downloadId);
    }
}

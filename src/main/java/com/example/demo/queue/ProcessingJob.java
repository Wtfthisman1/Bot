package com.example.demo.queue;

import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;

public record ProcessingJob(
        String id,
        long chatId,
        Path filePath,    // null => ещё не скачано
        String url,       // null => локальный файл
        State state
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
                chatId, null, url, State.NEW);
    }

    public static ProcessingJob newFile(long chatId, Path file) {
        Objects.requireNonNull(file, "file");
        return new ProcessingJob(UUID.randomUUID().toString(),
                chatId, file, null, State.DOWNLOADED);
    }

    public ProcessingJob withFile(Path file) {
        Objects.requireNonNull(file, "file");
        return new ProcessingJob(id, chatId, file, null, State.DOWNLOADED);
    }
}

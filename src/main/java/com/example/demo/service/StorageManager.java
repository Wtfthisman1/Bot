package com.example.demo.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;          // switched from Instant
import java.time.format.DateTimeFormatter;
import java.util.stream.Stream;

@Component
@Slf4j
public class StorageManager {

    @Value("${app.storage.base}")
    private String storageBase;
    private Path storageRoot;

    private static final DateTimeFormatter DTF =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS");

    /* ---------------- init ---------------- */

    @PostConstruct
    void init() throws IOException {
        storageRoot = Path.of(storageBase).toAbsolutePath();
        Files.createDirectories(storageRoot);
    }

    /* ---------------- public API ---------------- */

    public Path userRoot(long chatId) throws IOException {
        Path userPath = storageRoot.resolve(String.valueOf(chatId));
        Files.createDirectories(userPath);
        return userPath;
    }

    public Path uploadedPath(long chatId, String originalName) throws IOException {
        return ensureSubDir(chatId, "uploaded")
                .resolve(fileName(originalName, null));
    }

    public Path downloadedPath(long chatId, String url) throws IOException {
        String slug = slugFromUrl(url);
        if (slug.isBlank()) slug = "video";
        return ensureSubDir(chatId, "downloaded")
                .resolve(fileName(slug, ".mp4"));
    }

    public Path transcriptPath(long chatId, String baseName) throws IOException {
        return ensureSubDir(chatId, "transcripts")
                .resolve(fileName(baseName, ".txt"));
    }

    public Stream<Path> listFiles(long chatId) throws IOException {
        return Stream.concat(
                        Files.list(ensureSubDir(chatId, "uploaded")),
                        Files.list(ensureSubDir(chatId, "downloaded")))
                .filter(this::isVideo);
    }

    public Stream<Path> listTranscripts(long chatId) throws IOException {
        return Files.list(ensureSubDir(chatId, "transcripts"))
                .filter(p -> p.toString().endsWith(".txt"));
    }

    /* ---------------- helpers ---------------- */

    private String fileName(String base, String ext) {
        String ts = DTF.format(LocalDateTime.now());
        String safe = sanitize(base);
        return ts + "_" + safe + (ext != null ? ext : "");
    }

    private String sanitize(String name) {
        return name.replaceAll("[^\\w.-]", "_");
    }

    private String slugFromUrl(String url) {
        return url.replaceAll("https?://", "")
                .replaceAll("[^\\w]", "_")
                .replaceAll("_+", "_");
    }

    private Path ensureSubDir(long chatId, String dirName) throws IOException {
        Path dir = userRoot(chatId).resolve(dirName);
        Files.createDirectories(dir);
        return dir;
    }

    private boolean isVideo(Path p) {
        String n = p.getFileName().toString().toLowerCase();
        return n.endsWith(".mp4") || n.endsWith(".webm") ||
                n.endsWith(".mkv") || n.endsWith(".mov") ||
                n.endsWith(".avi");
    }
}

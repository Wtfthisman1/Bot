package Bot.download;

/**
 * Реестр одноразово-генерируемых ссылок на скачивание файлов.
 *
 * <p>Ответственность: выдаёт непредсказуемый токен (на базе {@link SecureRandom}),
 * хранит привязку токен → (путь к файлу, chatId, срок действия), валидирует токен
 * с учётом TTL и существования файла, периодически чистит просроченные записи.
 * Заменяет старую схему с предсказуемым {@code hashCode}-идентификатором и поиском
 * файла по всему хранилищу. Используется {@link Bot.download.DownloadService}
 * (регистрация) и {@link DownloadController} (резолв). Основные методы:
 * {@code register}, {@code resolve}, фоновая задача {@code purgeExpired}.</p>
 *
 * <p>Реестр переживает перезапуск: записи дублируются в TSV-файл
 * ({@code download.token.store}) и поднимаются обратно при старте. Без этого
 * ссылка с обещанным сроком в 24 часа умирала на первом же рестарте бота —
 * пользователь получал 404 «недействительный токен» через пару минут.</p>
 */
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Slf4j
public class DownloadTokenRegistry {

    /** Срок жизни ссылки в часах (по умолчанию 24). */
    @Value("${download.token.ttl-hours:24}")
    private int ttlHours;

    /** Файл, в котором реестр переживает перезапуск. */
    @Value("${download.token.store}")
    private String storePath;

    /** 24 байта ≈ 192 бита энтропии — подобрать перебором невозможно. */
    private static final int TOKEN_BYTES = 24;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    /** token → данные ссылки. ConcurrentHashMap — доступ из разных потоков. */
    private final Map<String, DownloadToken> tokens = new ConcurrentHashMap<>();

    private Path store;

    /**
     * Поднимает сохранённые токены с диска. Просроченные и указывающие
     * на исчезнувшие файлы записи отбрасываются сразу.
     */
    @PostConstruct
    void load() {
        store = Path.of(storePath).toAbsolutePath().normalize();
        if (!Files.exists(store)) {
            log.info("Файл download-токенов не найден, начинаем с пустого реестра: {}", store);
            return;
        }

        Instant now = Instant.now();
        int restored = 0;
        int dropped = 0;
        try {
            for (String line : Files.readAllLines(store, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                // token \t expiresAtEpochSeconds \t chatId \t absolutePath
                String[] parts = line.split("\t", 4);
                if (parts.length < 4) {
                    dropped++;
                    continue;
                }
                try {
                    Instant expiresAt = Instant.ofEpochSecond(Long.parseLong(parts[1]));
                    Path file = Path.of(parts[3]).toAbsolutePath().normalize();
                    if (expiresAt.isBefore(now) || !Files.isRegularFile(file)) {
                        dropped++;
                        continue;
                    }
                    tokens.put(parts[0], new DownloadToken(file, Long.parseLong(parts[2]), expiresAt));
                    restored++;
                } catch (RuntimeException e) {
                    dropped++;
                }
            }
        } catch (IOException e) {
            log.warn("Не удалось прочитать файл download-токенов: {}", store, e);
            return;
        }

        log.info("Восстановлено download-токенов: {}, отброшено: {}", restored, dropped);
        if (dropped > 0) {
            persistAll();
        }
    }

    /**
     * Регистрирует файл и возвращает непредсказуемый токен для ссылки.
     *
     * @param filePath путь к уже скачанному файлу
     * @param chatId   владелец загрузки (для аудита/логов)
     * @return токен, который вставляется в URL вида {baseUrl}/download/{token}
     */
    public String register(Path filePath, long chatId) {
        String token = generateToken();
        Instant expiresAt = Instant.now().plus(Duration.ofHours(ttlHours));
        Path normalized = filePath.toAbsolutePath().normalize();

        DownloadToken info = new DownloadToken(normalized, chatId, expiresAt);
        tokens.put(token, info);
        append(token, info);
        log.info("Зарегистрирована download-ссылка: chatId={}, file={}, expiresAt={}",
                chatId, normalized.getFileName(), expiresAt);
        return token;
    }

    /**
     * Возвращает путь к файлу по токену, если токен валиден, не просрочен
     * и файл по-прежнему существует. Иначе — пустой Optional.
     *
     * <p>Просроченные токены удаляются «лениво» прямо здесь, не дожидаясь
     * фоновой чистки.</p>
     */
    public Optional<Path> resolve(String token) {
        DownloadToken info = tokens.get(token);
        if (info == null) {
            return Optional.empty();
        }

        if (info.expiresAt().isBefore(Instant.now())) {
            tokens.remove(token);
            persistAll();
            log.debug("Токен просрочен и удалён: {}", token);
            return Optional.empty();
        }

        Path file = info.filePath();
        if (!Files.exists(file) || !Files.isRegularFile(file)) {
            log.warn("Файл для токена отсутствует на диске: file={}", file);
            return Optional.empty();
        }

        return Optional.of(file);
    }

    /**
     * Фоновая очистка просроченных токенов, чтобы карта не разрасталась.
     * Требует {@code @EnableScheduling} (включено в SpringBotApplication).
     */
    @Scheduled(fixedRate = 30 * 60 * 1000) // каждые 30 минут
    void purgeExpired() {
        Instant now = Instant.now();
        int before = tokens.size();
        tokens.entrySet().removeIf(e -> e.getValue().expiresAt().isBefore(now));
        int removed = before - tokens.size();
        if (removed > 0) {
            persistAll();
            log.debug("Очищено просроченных download-токенов: {}", removed);
        }
    }

    /* ───────── helpers ───────── */

    private String generateToken() {
        byte[] buf = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(buf);
        // URL-safe Base64 без паддинга: только [A-Za-z0-9-_], без '/' и '.'
        return URL_ENCODER.encodeToString(buf);
    }

    /** Дописывает одну запись — обычный путь регистрации, без переписывания файла. */
    private synchronized void append(String token, DownloadToken info) {
        try {
            Path parent = store.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(store, line(token, info), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            // Ссылка всё равно работает до перезапуска — не срываем выдачу файла
            log.warn("Не удалось сохранить download-токен на диск: {}", store, e);
        }
    }

    /** Переписывает файл целиком — после удаления записей. */
    private synchronized void persistAll() {
        List<String> lines = new ArrayList<>(tokens.size());
        tokens.forEach((token, info) -> lines.add(line(token, info)));
        try {
            Path parent = store.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(store, String.join("", lines), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            log.warn("Не удалось переписать файл download-токенов: {}", store, e);
        }
    }

    private String line(String token, DownloadToken info) {
        return token + '\t' + info.expiresAt().getEpochSecond() + '\t'
                + info.chatId() + '\t' + info.filePath() + '\n';
    }

    /** Запись о выданной ссылке. */
    private record DownloadToken(Path filePath, long chatId, Instant expiresAt) {}
}

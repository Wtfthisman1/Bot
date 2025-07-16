package com.example.demo.upload;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class UploadService {

    /** Базовый URL, например: http://myserver:8080 */
    @Value("${upload.base-url}")
    private String baseUrl;

    /** Срок действия токена (по умолчанию 1 ч.). */
    private static final Duration TTL = Duration.ofHours(1);

    /** token → info */
    private final Map<String, TokenInfo> tokens = new ConcurrentHashMap<>();

    /** Сгенерировать одноразовую ссылку вида  {baseUrl}/upload/{token} */
    public String generate(long chatId) {
        String token = UUID.randomUUID().toString().replace("-", "");
        tokens.put(token, new TokenInfo(chatId, Instant.now().plus(TTL)));
        return baseUrl + "/upload/" + token;
    }

    /** Проверить и погасить токен; возвращает chatId либо <code>null</code>. */
    public Long consume(String token) {
        TokenInfo info = tokens.remove(token);
        if (info == null || info.expireTime().isBefore(Instant.now()))
            return null;
        return info.chatId();
    }

    /** Периодически чистим просроченные токены, чтобы Map не разрасталась. */
    @Scheduled(fixedRate = 30 * 60 * 1000)   // каждые 30 мин
    void purgeExpired() {
        Instant now = Instant.now();
        tokens.entrySet().removeIf(e -> e.getValue().expireTime().isBefore(now));
    }

    /* ───────── record ───────── */
    private record TokenInfo(long chatId, Instant expireTime) {}
}

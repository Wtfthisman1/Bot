package com.example.demo.upload;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.security.SecureRandom;
import org.springframework.beans.factory.InitializingBean;

@Service
@RequiredArgsConstructor
@Slf4j
public class UploadService implements InitializingBean {

    /** Базовый URL, например: http://myserver:8080 */
    @Value("${upload.base-url:http://localhost:8080}")
    private String baseUrl;
    
    @Override
    public void afterPropertiesSet() {
        log.info("UploadService initialized with baseUrl: {}", baseUrl);
    }

    /** Длина токена */
    @Value("${upload.token.length:8}")
    private int tokenLength;

    /** Символы для токена */
    @Value("${upload.token.chars:ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789}")
    private String tokenChars;

    /** Срок действия токена в часах */
    @Value("${upload.token.ttl.hours:1}")
    private int tokenTtlHours;

    /** Генератор случайных чисел для коротких токенов */
    private static final SecureRandom RANDOM = new SecureRandom();

    /** token → info */
    private final Map<String, TokenInfo> tokens = new ConcurrentHashMap<>();

    /** Сгенерировать короткий токен */
    private String generateShortToken() {
        StringBuilder token = new StringBuilder(tokenLength);
        for (int i = 0; i < tokenLength; i++) {
            token.append(tokenChars.charAt(RANDOM.nextInt(tokenChars.length())));
        }
        return token.toString();
    }

    /** Сгенерировать одноразовую ссылку вида  {baseUrl}/upload/{token} */
    public String generate(long chatId) {
        String token = generateShortToken();
        tokens.put(token, new TokenInfo(chatId, Instant.now().plus(Duration.ofHours(tokenTtlHours))));
        
        // Принудительно используем правильный URL с портом
        String correctBaseUrl = "http://91.184.242.68:8080";
        String link = correctBaseUrl + "/upload/" + token;
        
        log.debug("Generated link: {}", link);
        log.debug("baseUrl from config: {}", baseUrl);
        
        return link;
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

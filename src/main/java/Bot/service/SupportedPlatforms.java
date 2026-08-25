package Bot.service;

/**
 * Проверка, что URL ведёт на поддерживаемую видео/аудио-платформу.
 *
 * <p>Ответственность: единая точка валидации внешних ссылок перед передачей их
 * в yt-dlp. Заменяет дублировавшуюся в {@link MessageHandler} логику и закрывает
 * путь скачивания ({@link DownloadService#createDownloadTask}), который раньше
 * принимал любой URL (потенциальный SSRF).</p>
 *
 * <p>Проверка идёт по фактическому <b>хосту</b> разобранного URL, а не по
 * подстроке: строка вроде {@code http://evil.example/?x=youtube.com} больше не
 * считается валидной. Поддерживаются поддомены (например {@code m.youtube.com}).</p>
 */
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.List;

@Component
@Slf4j
public class SupportedPlatforms {

    /** Базовые домены поддерживаемых платформ. */
    private static final List<String> ALLOWED_HOSTS = List.of(
            "youtube.com",
            "youtu.be",
            "vimeo.com",
            "tiktok.com",
            "instagram.com",
            "twitter.com",
            "x.com",
            "facebook.com",
            "fb.com"
    );

    /**
     * @return true, если URL валиден, использует http(s) и его хост входит в
     *         список разрешённых платформ (с учётом поддоменов).
     */
    public boolean isSupported(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }

        final URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            log.debug("Не удалось разобрать URL: {}", url);
            return false;
        }

        String scheme = uri.getScheme();
        if (scheme == null
                || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            return false;
        }

        String host = uri.getHost();
        if (host == null) {
            return false;
        }
        host = host.toLowerCase();

        for (String allowed : ALLOWED_HOSTS) {
            if (host.equals(allowed) || host.endsWith("." + allowed)) {
                return true;
            }
        }
        return false;
    }

    /** Человекочитаемый список поддерживаемых платформ для сообщений пользователю. */
    public String supportedListText() {
        return """
                🔗 Поддерживаемые платформы:
                • YouTube (youtube.com, youtu.be)
                • Vimeo (vimeo.com)
                • TikTok (tiktok.com)
                • Instagram (instagram.com)
                • Twitter/X (twitter.com, x.com)
                • Facebook (facebook.com)""";
    }
}

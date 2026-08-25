package Bot.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Проверка белого списка платформ — это же защита от SSRF на пути в yt-dlp.
 */
class SupportedPlatformsTest {

    private final SupportedPlatforms platforms = new SupportedPlatforms();

    @ParameterizedTest
    @ValueSource(strings = {
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://youtu.be/dQw4w9WgXcQ",
            "http://m.youtube.com/watch?v=x",
            "https://vimeo.com/123",
            "https://www.tiktok.com/@user/video/1",
            "https://x.com/user/status/1"
    })
    void acceptsSupportedPlatforms(String url) {
        assertThat(platforms.isSupported(url)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // хост подставной, «youtube.com» только в query — проверка по подстроке тут ломалась
            "http://evil.example/?x=youtube.com",
            "https://youtube.com.evil.example/video",
            "file:///etc/passwd",
            "http://169.254.169.254/latest/meta-data/",
            "не ссылка"
    })
    void rejectsEverythingElse(String url) {
        assertThat(platforms.isSupported(url)).isFalse();
    }

    @Test
    void rejectsNullAndBlank() {
        assertThat(platforms.isSupported(null)).isFalse();
        assertThat(platforms.isSupported("   ")).isFalse();
    }
}

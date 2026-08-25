package Bot.download;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DownloadTokenRegistryTest {

    @TempDir Path tmp;

    private Path store;
    private Path file;

    @BeforeEach
    void setUp() throws IOException {
        store = tmp.resolve("tokens.tsv");
        file = Files.writeString(tmp.resolve("video.mp4"), "data");
    }

    private DownloadTokenRegistry registry(int ttlHours) {
        DownloadTokenRegistry registry = new DownloadTokenRegistry();
        ReflectionTestUtils.setField(registry, "ttlHours", ttlHours);
        ReflectionTestUtils.setField(registry, "storePath", store.toString());
        ReflectionTestUtils.invokeMethod(registry, "load");
        return registry;
    }

    @Test
    void registeredTokenResolvesToTheFile() {
        DownloadTokenRegistry registry = registry(24);

        String token = registry.register(file, 1L);

        assertThat(token).isNotBlank().matches("[A-Za-z0-9_-]+");
        assertThat(registry.resolve(token)).contains(file.toAbsolutePath().normalize());
    }

    @Test
    void unknownTokenDoesNotResolve() {
        assertThat(registry(24).resolve("nope")).isEmpty();
    }

    @Test
    void tokensAreUnpredictableAndUnique() {
        DownloadTokenRegistry registry = registry(24);
        assertThat(registry.register(file, 1L)).isNotEqualTo(registry.register(file, 1L));
    }

    @Test
    void expiredTokenIsRejected() {
        DownloadTokenRegistry registry = registry(-1);

        String token = registry.register(file, 1L);

        assertThat(registry.resolve(token)).isEmpty();
    }

    @Test
    void deletedFileMakesTokenUnusable() throws IOException {
        DownloadTokenRegistry registry = registry(24);
        String token = registry.register(file, 1L);

        Files.delete(file);

        assertThat(registry.resolve(token)).isEmpty();
    }

    /** Ссылка обещает 24 часа — она обязана пережить рестарт бота. */
    @Test
    void tokenSurvivesRestart() {
        String token = registry(24).register(file, 1L);

        DownloadTokenRegistry afterRestart = registry(24);

        assertThat(afterRestart.resolve(token)).contains(file.toAbsolutePath().normalize());
    }

    @Test
    void expiredEntriesAreDroppedOnRestart() {
        String token = registry(-1).register(file, 1L);

        assertThat(registry(24).resolve(token)).isEmpty();
    }
}

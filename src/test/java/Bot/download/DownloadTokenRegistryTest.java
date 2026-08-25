package Bot.download;

/**
 * Ссылки на скачивание: срок годности, исчезнувший файл и — главное —
 * выживание после перезапуска бота. Ради последнего реестр и переехал в базу,
 * поэтому тест идёт в настоящий Postgres.
 */
import Bot.owner.Owner;
import Bot.support.PostgresTestContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PostgresTestContainer.class, DownloadTokenRegistry.class})
// Без общей транзакции теста: «пережил рестарт» проверяется только тем,
// что запись реально попала в базу, а не висит в незакрытой транзакции
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DownloadTokenRegistryTest {

    private static final Owner OWNER = Owner.telegram(1L);

    @TempDir Path tmp;

    @Autowired DownloadTokenRegistry registry;
    @Autowired DownloadTokenRepository repository;

    private Path file;

    @BeforeEach
    void setUp() throws IOException {
        file = Files.writeString(tmp.resolve("video.mp4"), "data");
        withTtl(24);
    }

    @AfterEach
    void clean() {
        repository.deleteAll();
    }

    /** Срок жизни ссылки — настройка, поэтому подменяем её как в конфиге. */
    private void withTtl(int hours) {
        ReflectionTestUtils.setField(registry, "ttlHours", hours);
    }

    @Test
    void registeredTokenResolvesToTheFile() {
        String token = registry.register(file, OWNER);

        assertThat(token).isNotBlank().matches("[A-Za-z0-9_-]+");
        assertThat(registry.resolve(token)).contains(file.toAbsolutePath().normalize());
    }

    @Test
    void unknownTokenDoesNotResolve() {
        assertThat(registry.resolve("nope")).isEmpty();
    }

    @Test
    void tokensAreUnpredictableAndUnique() {
        assertThat(registry.register(file, OWNER)).isNotEqualTo(registry.register(file, OWNER));
    }

    @Test
    void expiredTokenIsRejected() {
        withTtl(-1);

        String token = registry.register(file, OWNER);

        assertThat(registry.resolve(token)).isEmpty();
    }

    @Test
    void deletedFileMakesTokenUnusable() throws IOException {
        String token = registry.register(file, OWNER);

        Files.delete(file);

        assertThat(registry.resolve(token)).isEmpty();
    }

    /** Ссылка обещает 24 часа — она обязана пережить рестарт бота. */
    @Test
    void tokenSurvivesRestart() {
        String token = registry.register(file, OWNER);

        // «Перезапуск»: у нового экземпляра нет никакого состояния в памяти
        DownloadTokenRegistry afterRestart = new DownloadTokenRegistry(repository);
        ReflectionTestUtils.setField(afterRestart, "ttlHours", 24);

        assertThat(afterRestart.resolve(token)).contains(file.toAbsolutePath().normalize());
    }

    @Test
    void expiredEntriesAreSweptAway() {
        withTtl(-1);
        registry.register(file, OWNER);

        registry.purgeExpired();

        assertThat(repository.count()).isZero();
    }
}

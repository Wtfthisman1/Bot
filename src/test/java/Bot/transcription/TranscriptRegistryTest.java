package Bot.transcription;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Реестр расшифровок: доступ по идентификатору, отказ чужому чату и
 * выживание записей после перезапуска бота.
 */
class TranscriptRegistryTest {

    private static final long OWNER = 42L;

    @TempDir Path tmp;

    private Path store;
    private Path txt;

    @BeforeEach
    void setUp() throws IOException {
        store = tmp.resolve("transcripts.tsv");
        txt = Files.writeString(tmp.resolve("лекция.txt"), "расшифровка");
    }

    private TranscriptRegistry registry() {
        TranscriptRegistry registry = new TranscriptRegistry();
        ReflectionTestUtils.setField(registry, "storePath", store.toString());
        ReflectionTestUtils.invokeMethod(registry, "load");
        return registry;
    }

    @Test
    void registeredTranscriptResolvesForItsOwner() {
        TranscriptRegistry registry = registry();

        String id = registry.register(txt, OWNER);

        assertThat(id).isNotBlank().matches("[A-Za-z0-9_-]+");
        assertThat(registry.resolve(id, OWNER))
                .map(TranscriptRegistry.Transcript::txt)
                .contains(txt.toAbsolutePath().normalize());
    }

    /** Идентификатор из чужого чата не должен открывать доступ к расшифровке. */
    @Test
    void otherChatCannotResolve() {
        TranscriptRegistry registry = registry();
        String id = registry.register(txt, OWNER);

        assertThat(registry.resolve(id, OWNER + 1)).isEmpty();
    }

    @Test
    void unknownIdDoesNotResolve() {
        assertThat(registry().resolve("нет-такого", OWNER)).isEmpty();
    }

    @Test
    void idsAreUnique() {
        TranscriptRegistry registry = registry();
        assertThat(registry.register(txt, OWNER)).isNotEqualTo(registry.register(txt, OWNER));
    }

    /** Ради этого реестр и пишется на диск: кнопки должны пережить рестарт. */
    @Test
    void entriesSurviveRestart() {
        String id = registry().register(txt, OWNER);

        assertThat(registry().resolve(id, OWNER)).isPresent();
    }

    /** Файл удалила ночная чистка — запись бесполезна и не поднимается. */
    @Test
    void entryIsDroppedWhenFileIsGone() throws IOException {
        String id = registry().register(txt, OWNER);
        Files.delete(txt);

        assertThat(registry().resolve(id, OWNER)).isEmpty();
    }

    @Test
    void purgeRemovesEntriesWithoutFiles() throws IOException {
        TranscriptRegistry registry = registry();
        String id = registry.register(txt, OWNER);
        Files.delete(txt);

        ReflectionTestUtils.invokeMethod(registry, "purgeMissing");

        assertThat(registry.resolve(id, OWNER)).isEmpty();
        assertThat(Files.readString(store)).isEmpty();
    }
}

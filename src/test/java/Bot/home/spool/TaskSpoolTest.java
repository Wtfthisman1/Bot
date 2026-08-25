package Bot.home.spool;

/**
 * Спул обязан пережить перезапуск бота и сохранить порядок задач.
 */
import Bot.owner.Owner;
import Bot.processing.MediaKind;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TaskSpoolTest {

    private static final Owner OWNER = Owner.telegram(77L);

    @TempDir Path dir;
    private TaskSpool spool;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        spool = new TaskSpool(dir.toString(), mapper);
    }

    @Test
    void taskSurvivesRestart() {
        spool.add(SpooledTask.transcribeLink(OWNER, "https://youtu.be/x"));

        // «Перезапуск»: новый экземпляр поверх того же каталога
        TaskSpool restarted = new TaskSpool(dir.toString(),
                new ObjectMapper().registerModule(new JavaTimeModule()));

        assertThat(restarted.pending()).singleElement()
                .satisfies(task -> {
                    assertThat(task.url()).isEqualTo("https://youtu.be/x");
                    assertThat(task.owner()).isEqualTo(OWNER);
                });
    }

    @Test
    void orderOfArrivalIsKept() {
        spool.add(SpooledTask.transcribeLink(OWNER, "первая"));
        spool.add(SpooledTask.downloadLink(OWNER, "вторая", MediaKind.VIDEO));
        spool.add(SpooledTask.transcribeLink(OWNER, "третья"));

        assertThat(spool.pending()).extracting(SpooledTask::url)
                .containsExactly("первая", "вторая", "третья");
    }

    @Test
    void removedTaskDoesNotComeBack() {
        SpooledTask task = SpooledTask.transcribeLink(OWNER, "https://youtu.be/x");
        spool.add(task);

        spool.remove(task);

        assertThat(spool.pending()).isEmpty();
        assertThat(spool.size()).isZero();
    }

    /** Повторная запись той же задачи не размножает её: имя файла то же. */
    @Test
    void retriedTaskStaysSingle() {
        SpooledTask task = SpooledTask.transcribeLink(OWNER, "https://youtu.be/x");
        spool.add(task);

        spool.replace(task.afterFailedAttempt());

        assertThat(spool.pending()).singleElement()
                .satisfies(stored -> assertThat(stored.attempts()).isEqualTo(1));
    }

    @Test
    void ownTasksAreCountedSeparately() {
        spool.add(SpooledTask.transcribeLink(OWNER, "моя"));
        spool.add(SpooledTask.transcribeLink(Owner.telegram(999L), "чужая"));

        assertThat(spool.countFor(OWNER)).isEqualTo(1);
    }

    @Test
    void oldTasksAreFoundByAge() {
        spool.add(SpooledTask.transcribeLink(OWNER, "свежая"));
        spool.add(new SpooledTask(UUID.randomUUID(), Instant.now().minus(Duration.ofDays(5)),
                SpooledTask.Kind.TRANSCRIBE_LINK, OWNER, "древняя", null, null, 0));

        assertThat(spool.olderThan(Duration.ofDays(3)))
                .extracting(SpooledTask::url).containsExactly("древняя");
    }

    /** Битая запись не должна утаскивать за собой весь спул. */
    @Test
    void brokenFileIsSkipped() throws Exception {
        spool.add(SpooledTask.transcribeLink(OWNER, "целая"));
        Files.writeString(dir.resolve("0-broken.json"), "{это не json");

        assertThat(spool.pending()).extracting(SpooledTask::url).containsExactly("целая");
    }
}

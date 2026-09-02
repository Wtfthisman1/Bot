package Bot.service;

/**
 * Ночная чистка: две недели — это две недели для всех.
 *
 * <p>Проверяется то, что молча не работало: каталог аккаунта сайта называется
 * {@code acc-<uuid>}, а чистка приводила имя каталога к числу и всё нечисловое
 * пропускала. У всех, кто пришёл с сайта, записи не удалялись вовсе — ни через
 * две недели, ни когда-либо.</p>
 */
import Bot.owner.Owner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FileCleanupWorkerTest {

    private static final Owner CHAT = Owner.telegram(REDACTED_CHAT_IDL);
    private static final Owner ACCOUNT = Owner.account(UUID.randomUUID().toString());

    @TempDir Path root;

    private StorageManager storage;
    private FileCleanupWorker worker;

    @BeforeEach
    void setUp() throws IOException {
        storage = new StorageManager();
        ReflectionTestUtils.setField(storage, "storageBase", root.toString());
        storage.init();

        worker = new FileCleanupWorker(storage);
        ReflectionTestUtils.setField(worker, "retentionDays", 14);
        ReflectionTestUtils.setField(worker, "cleanupEnabled", true);
    }

    @Test
    void oldRecordsGoForChatsAndAccountsAlike() throws IOException {
        Path chatFile = aged(CHAT, "downloaded", "старое.mp4", Duration.ofDays(20));
        Path accountFile = aged(ACCOUNT, "uploaded", "старое.mp4", Duration.ofDays(20));

        worker.cleanupOldFiles();

        assertThat(chatFile).doesNotExist();
        assertThat(accountFile).doesNotExist();
    }

    @Test
    void freshRecordsStay() throws IOException {
        Path fresh = aged(ACCOUNT, "uploaded", "вчерашнее.mp4", Duration.ofDays(1));

        worker.cleanupOldFiles();

        assertThat(fresh).exists();
    }

    /** Расшифровки живут дольше записей: они весят ничего, а нужны и через месяц. */
    @Test
    void transcriptsSurvive() throws IOException {
        Path transcript = aged(ACCOUNT, "transcripts", "старое.txt", Duration.ofDays(20));

        worker.cleanupOldFiles();

        assertThat(transcript).exists();
    }

    private Path aged(Owner owner, String dirName, String fileName, Duration age)
            throws IOException {
        Path dir = storage.userRoot(owner).resolve(dirName);
        Files.createDirectories(dir);
        Path file = Files.writeString(dir.resolve(fileName), "запись");
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().minus(age)));
        return file;
    }
}

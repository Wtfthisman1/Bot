package Bot.home.spool;

/**
 * Очередь задач, ждущих пробуждения дома, — файлами на диске VPS.
 *
 * <p>Ответственность: пережить перезапуск бота и выдать задачи в том порядке,
 * в каком их прислали. Базы на VPS нет и не будет: там 960 МБ памяти, ради
 * десятка отложенных задач Postgres не поднимают. Файл на задачу — самый
 * дешёвый способ получить долговечность и порядок сразу.</p>
 *
 * <p>Запись идёт во временный файл и переименовывается: {@code rename} внутри
 * одной файловой системы атомарен, поэтому оборванная на середине запись не
 * оставит после себя полузадачу, которую потом никто не разберёт.</p>
 *
 * <p>Порядок задаётся моментом создания из самой записи, а не именем файла:
 * имя огрублено до миллисекунд, и две задачи, попавшие в одну, встали бы в
 * произвольном порядке — по случайному {@code UUID}. Момент внутри процесса
 * строго возрастает, см. {@link SpooledTask}.</p>
 */
import Bot.config.Profiles;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

@Profile("!" + Profiles.HOME)
@Component
@Slf4j
public class TaskSpool {

    private final Path directory;
    private final ObjectMapper mapper;

    public TaskSpool(@Value("${spool.dir:${user.dir}/spool}") String directory,
                     ObjectMapper mapper) {
        this.directory = Path.of(directory);
        this.mapper = mapper;
    }

    /** Кладёт задачу в спул. */
    public void add(SpooledTask task) {
        try {
            Files.createDirectories(directory);
            Path target = directory.resolve(fileName(task));
            Path temp = directory.resolve(fileName(task) + ".tmp");
            mapper.writeValue(temp.toFile(), task);
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            log.info("Задача отложена до пробуждения дома: id={}, владелец={}",
                    task.shortId(), task.owner());
        } catch (IOException e) {
            // Записать не смогли — значит, задачу принимать нельзя вовсе:
            // пообещать и потерять хуже, чем сразу отказать
            throw new UncheckedIOException("Не удалось записать задачу в спул", e);
        }
    }

    /** Заменяет запись — например, после неудачной попытки отдать её домой. */
    public void replace(SpooledTask task) {
        add(task);
    }

    /** Убирает задачу: она либо ушла домой, либо оказалась безнадёжной. */
    public void remove(SpooledTask task) {
        try {
            Files.deleteIfExists(directory.resolve(fileName(task)));
        } catch (IOException e) {
            // Не удалили — задача уйдёт домой второй раз. Неприятно, но
            // терпимо: молча потерять её было бы хуже
            log.warn("Не удалось убрать задачу из спула: id={}", task.shortId(), e);
        }
    }

    /** Задачи в порядке поступления. Битые записи пропускаются с жалобой в лог. */
    public List<SpooledTask> pending() {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(directory)) {
            List<SpooledTask> tasks = new ArrayList<>();
            files.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .forEach(path -> read(path).ifPresent(tasks::add));
            tasks.sort(Comparator.comparing(SpooledTask::createdAt)
                    .thenComparing(SpooledTask::id));
            return tasks;
        } catch (IOException e) {
            log.error("Не удалось прочитать спул: {}", directory, e);
            return List.of();
        }
    }

    public int size() {
        return pending().size();
    }

    /** Сколько задач ждёт у этого владельца — из этого собирается его «Статус». */
    public long countFor(Bot.owner.Owner owner) {
        return pending().stream().filter(task -> task.owner().equals(owner)).count();
    }

    /** Протухшие задачи: повторять их через неделю бессмысленно. */
    public List<SpooledTask> olderThan(Duration age) {
        Instant edge = Instant.now().minus(age);
        return pending().stream().filter(task -> task.createdAt().isBefore(edge)).toList();
    }

    /* ───────── helpers ───────── */

    private java.util.Optional<SpooledTask> read(Path path) {
        try {
            return java.util.Optional.of(mapper.readValue(path.toFile(), SpooledTask.class));
        } catch (IOException e) {
            log.error("Битая запись в спуле, пропускаю: {}", path.getFileName(), e);
            return java.util.Optional.empty();
        }
    }

    private static String fileName(SpooledTask task) {
        // Момент в начале имени задаёт порядок обхода; id разводит записи,
        // созданные в одну миллисекунду
        return "%d-%s.json".formatted(task.createdAt().toEpochMilli(), task.id());
    }
}

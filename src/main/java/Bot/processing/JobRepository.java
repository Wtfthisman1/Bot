package Bot.processing;

/**
 * Доступ к таблице задач.
 *
 * <p>Ответственность: выборка следующей задачи очереди и запросы по владельцу.
 * Всё остальное — обычные методы {@link JpaRepository}.</p>
 */
import Bot.owner.Owner;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JobRepository extends JpaRepository<JobEntity, UUID> {

    /**
     * Забирает самую старую задачу очереди, блокируя строку до конца транзакции.
     *
     * <p>{@code SKIP LOCKED} — то, ради чего здесь именно Postgres: воркеры
     * ходят за задачами одновременно, и без него второй ждал бы, пока первый
     * отпустит строку, вместо того чтобы взять следующую. Возвращается только
     * идентификатор: сущность грузится следом уже под блокировкой.</p>
     */
    @Query(value = """
            select id from jobs
             where state = 'QUEUED'
             order by created_at
             limit 1
             for update skip locked
            """, nativeQuery = true)
    Optional<UUID> lockNextQueued();

    /** Задача чистого скачивания по её внутреннему идентификатору. */
    Optional<JobEntity> findByDownloadId(String downloadId);

    /** Незавершённые загрузки владельца — то, что показывает «Статус». */
    List<JobEntity> findByOwnerTypeAndOwnerIdAndDownloadIdIsNotNullAndStateInOrderByCreatedAtDesc(
            Owner.OwnerType ownerType, String ownerId, List<JobState> states);

    /** Сколько задач ждёт своей очереди — для логов и метрик. */
    long countByState(JobState state);

    /** Сколько задач владельца сейчас в этом состоянии — ответ на «Статус». */
    long countByOwnerTypeAndOwnerIdAndState(Owner.OwnerType ownerType, String ownerId, JobState state);

    /**
     * Возвращает в очередь задачи, застрявшие в работе.
     *
     * <p>Вызывается на старте: если бота выключили посреди транскрипции, строка
     * осталась в RUNNING и её больше никто не подберёт. Пока приложение одно,
     * «в работе на старте» однозначно означает «осталось от прошлого запуска».
     * Когда воркер уедет на отдельную машину (фаза 2), этого станет мало —
     * понадобится отметка о том, что воркер жив.</p>
     */
    @Modifying
    @Query("""
            update JobEntity j
               set j.state = Bot.processing.JobState.QUEUED,
                   j.startedAt = null,
                   j.updatedAt = :now
             where j.state = Bot.processing.JobState.RUNNING
            """)
    int requeueRunning(@Param("now") java.time.Instant now);
}

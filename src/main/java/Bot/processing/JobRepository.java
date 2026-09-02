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

import java.time.Instant;
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
     * Сколько расшифровок владелец начал с указанного момента — это и есть
     * израсходованная квота.
     *
     * <p>{@code downloadId is null} отделяет расшифровку от чистого скачивания:
     * у задачи «скачать файл» идентификатор загрузки есть, и квоту она не
     * тратит. Сорвавшиеся и отменённые не считаются: человек не виноват, что
     * ссылка оказалась битой, и не должен платить лимитом за то, что сам
     * остановил.</p>
     */
    long countByOwnerTypeAndOwnerIdAndDownloadIdIsNullAndStateNotInAndCreatedAtGreaterThanEqual(
            Owner.OwnerType ownerType, String ownerId, List<JobState> states, Instant since);

    /** Сколько задач владельца ещё не доделано — предел на одного заказчика. */
    long countByOwnerTypeAndOwnerIdAndStateIn(
            Owner.OwnerType ownerType, String ownerId, List<JobState> states);

    /** Незавершённые задачи владельца — их показывает «Статус» и их можно отменить. */
    List<JobEntity> findByOwnerTypeAndOwnerIdAndStateInOrderByCreatedAtDesc(
            Owner.OwnerType ownerType, String ownerId, List<JobState> states);

    /** История задач владельца — то, что показывает страница «мои задачи». */
    List<JobEntity> findTop200ByOwnerTypeAndOwnerIdOrderByCreatedAtDesc(
            Owner.OwnerType ownerType, String ownerId);

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

package Bot.insight;

/**
 * Доступ к таблице обработок.
 *
 * <p>Ответственность: очередь к видеокарте и выборка по задаче. Очередь взята
 * та же, что у расшифровок ({@code FOR UPDATE SKIP LOCKED}) — по той же
 * причине: строку не должны взять двое.</p>
 */
import Bot.processing.JobState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InsightRepository extends JpaRepository<InsightEntity, Long> {

    /** Обработки задачи, новые сверху. */
    List<InsightEntity> findByJobIdOrderByCreatedAtDesc(UUID jobId);

    /** Уже заказанное и ещё не посчитанное — второй такой же заказ не нужен. */
    boolean existsByJobIdAndStateIn(UUID jobId, List<JobState> states);

    @Query(value = """
            select id from transcript_insights
             where state = 'QUEUED'
             order by created_at
             limit 1
             for update skip locked
            """, nativeQuery = true)
    Optional<Long> lockNextQueued();
}

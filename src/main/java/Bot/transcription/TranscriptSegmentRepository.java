package Bot.transcription;

/**
 * Доступ к сегментам расшифровки.
 *
 * <p>Ответственность: чтение расшифровки одной задачи по порядку и полная
 * замена её сегментов. Всё остальное — обычные методы {@link JpaRepository}.</p>
 */
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface TranscriptSegmentRepository extends JpaRepository<TranscriptSegmentEntity, Long> {

    List<TranscriptSegmentEntity> findByJobIdOrderByOrd(UUID jobId);

    boolean existsByJobId(UUID jobId);

    /**
     * Убирает прежние сегменты задачи.
     *
     * <p>Нужно перед повторным разбором: задачу могли пересчитать, и остатки
     * старой расшифровки перемешались бы с новой — уникальность по
     * {@code (job_id, ord)} поймала бы это уже посреди вставки.</p>
     */
    @Modifying
    @Query("delete from TranscriptSegmentEntity s where s.jobId = :jobId")
    int deleteByJobId(UUID jobId);
}

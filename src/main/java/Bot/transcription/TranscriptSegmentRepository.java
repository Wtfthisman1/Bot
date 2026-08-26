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

import java.util.Collection;
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

    /**
     * Ищет реплики среди указанных задач.
     *
     * <p>{@code websearch_to_tsquery}, а не {@code plainto_tsquery}: человек
     * пишет в строку поиска то же, что писал бы в поисковике — кавычки для
     * точной фразы, {@code or}, минус для исключения, — и разбирать это «всё
     * через И» значило бы молча потерять половину запроса.</p>
     *
     * <p>{@code ts_headline} возвращает кусок текста вокруг попадания: реплика
     * бывает длинной, а в списке нужно видеть, за что зацепилось.</p>
     */
    @Query(value = """
            select s.job_id,
                   s.start_ms,
                   s.speaker,
                   ts_headline('russian', s.text, query,
                               'StartSel=' || :open || ', StopSel=' || :close
                               || ', MaxWords=28, MinWords=10, ShortWord=2')
              from transcript_segments s,
                   websearch_to_tsquery('russian', :query) query
             where s.job_id in (:jobIds)
               and s.search @@ query
             order by ts_rank(s.search, query) desc, s.start_ms
             limit :limit
            """, nativeQuery = true)
    List<Object[]> search(Collection<UUID> jobIds, String query,
                          String open, String close, int limit);
}

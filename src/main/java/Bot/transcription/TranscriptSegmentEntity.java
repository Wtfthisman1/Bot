package Bot.transcription;

/**
 * Строка таблицы {@code transcript_segments} — кусок расшифровки со временем.
 *
 * <p>Ответственность: хранение. Сегмент — это то, что Whisper распознал между
 * двумя отметками времени; диаризация дополняет его меткой говорящего, а
 * редактор в кабинете правит текст.</p>
 *
 * <p>Время хранится в миллисекундах целым числом, а не в секундах дробью:
 * дробь пришла бы из json как {@code double}, и сравнение границ сегментов
 * зависело бы от округления.</p>
 */
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "transcript_segments")
@Getter
@Setter
@NoArgsConstructor
public class TranscriptSegmentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_id", nullable = false)
    private UUID jobId;

    /** Порядок в расшифровке: сегменты диаризации могут начинаться одновременно. */
    @Column(nullable = false)
    private int ord;

    @Column(name = "start_ms", nullable = false)
    private int startMs;

    @Column(name = "end_ms", nullable = false)
    private int endMs;

    /** {@code SPEAKER_00} и подобное; {@code null} — диаризации не было. */
    private String speaker;

    @Column(nullable = false)
    private String text;

    /** Правил ли человек этот сегмент руками. */
    @Column(nullable = false)
    private boolean edited;
}

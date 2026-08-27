package Bot.insight;

/**
 * Строка таблицы {@code transcript_insights} — один заказ на обработку.
 *
 * <p>Ответственность: хранение. Заказ живёт дольше страницы, на которой его
 * сделали: модель считает минутами, человек за это время успевает уйти и
 * вернуться, а результат должен ждать его на месте.</p>
 *
 * <p>Состояние повторяет {@link Bot.processing.JobState} намеренно: обработка —
 * такая же очередь к видеокарте, и заводить для неё второй словарь состояний
 * значило бы объяснять человеку разницу, которой нет.</p>
 */
import Bot.processing.JobState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "transcript_insights")
@Getter
@Setter
@NoArgsConstructor
public class InsightEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_id", nullable = false)
    private UUID jobId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private InsightKind kind;

    /** Доля от исходного текста в процентах; {@code null} — не выжимка. */
    private Integer ratio;

    /** О чём спрашивали; {@code null} — не разбор по теме. */
    private String topic;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private JobState state;

    /** Ответ модели как есть, без разметки. */
    @Column(columnDefinition = "text")
    private String text;

    private String model;

    @Column(columnDefinition = "text")
    private String error;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    /** Ждёт очереди или считается прямо сейчас — страница обновляет себя сама. */
    public boolean isPending() {
        return state == JobState.QUEUED || state == JobState.RUNNING;
    }
}

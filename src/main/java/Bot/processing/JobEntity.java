package Bot.processing;

/**
 * Строка таблицы {@code jobs} — задача, как она лежит в базе.
 *
 * <p>Ответственность: хранение. Работать с ней напрямую незачем: воркеры и
 * сервисы получают {@link ProcessingJob} через {@link JobStore}, а сюда
 * попадают поля, которые нужны только для восстановления после перезапуска и
 * для истории — счётчик попыток, время начала и конца, текст ошибки.</p>
 */
import Bot.owner.Owner;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "jobs")
@Getter
@Setter
@NoArgsConstructor
public class JobEntity {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "owner_type", nullable = false)
    private Owner.OwnerType ownerType;

    @Column(name = "owner_id", nullable = false)
    private String ownerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProcessingJob.Stage stage;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private JobState state;

    private String url;

    @Enumerated(EnumType.STRING)
    @Column(name = "media_kind", nullable = false)
    private MediaKind mediaKind;

    @Column(name = "file_path")
    private String filePath;

    @Column(name = "transcript_path")
    private String transcriptPath;

    @Column(name = "download_id")
    private String downloadId;

    private String error;

    /** Больше единицы — задачу подбирали заново после падения воркера. */
    @Column(nullable = false)
    private int attempts;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    public Owner owner() {
        return new Owner(ownerType, ownerId);
    }
}

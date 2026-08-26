package Bot.transcription;

/**
 * Имя говорящего, данное человеком: {@code SPEAKER_00} → «Ведущий».
 *
 * <p>Ключ составной — задача и метка диаризации: одна и та же метка в разных
 * разговорах означает разных людей.</p>
 */
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.util.UUID;

@Entity
@Table(name = "transcript_speakers")
@IdClass(TranscriptSpeakerEntity.Key.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class TranscriptSpeakerEntity {

    @Id
    @Column(name = "job_id", nullable = false)
    private UUID jobId;

    @Id
    @Column(nullable = false)
    private String label;

    @Column(nullable = false)
    private String name;

    /** Составной ключ: JPA требует отдельный класс, даже когда полей всего два. */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Key implements Serializable {
        private UUID jobId;
        private String label;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key key)) return false;
            return java.util.Objects.equals(jobId, key.jobId)
                    && java.util.Objects.equals(label, key.label);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(jobId, label);
        }
    }
}

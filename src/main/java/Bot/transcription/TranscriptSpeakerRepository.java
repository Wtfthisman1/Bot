package Bot.transcription;

/** Имена говорящих одной расшифровки. */
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TranscriptSpeakerRepository
        extends JpaRepository<TranscriptSpeakerEntity, TranscriptSpeakerEntity.Key> {

    List<TranscriptSpeakerEntity> findByJobId(UUID jobId);
}

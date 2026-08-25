package Bot.download;

/**
 * Строка таблицы {@code download_tokens} — выданная ссылка на скачивание.
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

@Entity
@Table(name = "download_tokens")
@Getter
@Setter
@NoArgsConstructor
public class DownloadTokenEntity {

    @Id
    private String token;

    @Enumerated(EnumType.STRING)
    @Column(name = "owner_type", nullable = false)
    private Owner.OwnerType ownerType;

    @Column(name = "owner_id", nullable = false)
    private String ownerId;

    @Column(name = "file_path", nullable = false)
    private String filePath;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public Owner owner() {
        return new Owner(ownerType, ownerId);
    }
}

package Bot.account;

/**
 * Строка таблицы {@code account_identities} — внешний вход в аккаунт.
 *
 * <p>Одному аккаунту принадлежит сколько угодно личностей, каждая — ровно
 * одному аккаунту: пара «провайдер + идентификатор» уникальна в базе.</p>
 */
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
@Table(name = "account_identities")
@Getter
@Setter
@NoArgsConstructor
public class AccountIdentityEntity {

    @Id
    private UUID id;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private IdentityProvider provider;

    @Column(name = "provider_user_id", nullable = false)
    private String providerUserId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}

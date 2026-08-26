package Bot.account;

/**
 * Строка таблицы {@code account_link_codes} — код привязки Telegram.
 *
 * <p>Человек берёт код в кабинете и присылает его боту. До этого момента бот
 * не знает, кому принадлежит переписка: chat id — это не аккаунт.</p>
 */
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "account_link_codes")
@Getter
@Setter
@NoArgsConstructor
public class LinkCodeEntity {

    @Id
    private String code;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** Заполнено — код уже сработал и второй раз не сработает. */
    @Column(name = "used_at")
    private Instant usedAt;
}

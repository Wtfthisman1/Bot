package Bot.account;

/**
 * Строка таблицы {@code bot_login_links} — одноразовая ссылка входа на сайт.
 *
 * <p>Живёт одну попытку входа: выдана чату, открыта в браузере, погашена.
 * Чат известен с самого начала — ссылку просит он сам, и подтверждать её
 * потом нечем и незачем.</p>
 */
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "bot_login_links")
@Getter
@Setter
@NoArgsConstructor
public class BotLoginLinkEntity {

    @Id
    private String token;

    /** Чат, который попросил ссылку, — в его аккаунт она и пустит. */
    @Column(name = "chat_id", nullable = false)
    private Long chatId;

    @Column(name = "display_name")
    private String displayName;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** Заполняется при входе: по одной ссылке входят один раз. */
    @Column(name = "used_at")
    private Instant usedAt;
}

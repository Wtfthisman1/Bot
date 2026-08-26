package Bot.account;

/**
 * Строка таблицы {@code accounts} — аккаунт на сайте.
 *
 * <p>Пароль хранится только хешем: {@link AccountService} считает его BCrypt,
 * а сюда исходный пароль не попадает вовсе.</p>
 *
 * <p>Ни почты, ни пароля может не быть: аккаунт, заведённый через Telegram,
 * не знает ни того, ни другого. Что человек предъявляет при входе, описывают
 * {@link AccountIdentityEntity} и эти три колонки вместе.</p>
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
@Table(name = "accounts")
@Getter
@Setter
@NoArgsConstructor
public class AccountEntity {

    @Id
    private UUID id;

    /** Всегда в нижнем регистре — так требует ограничение в базе. */
    @Column(unique = true)
    private String email;

    /** Пусто у аккаунта без пароля — вход туда только через провайдера. */
    @Column(name = "password_hash")
    private String passwordHash;

    @Column(name = "display_name")
    private String displayName;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;
}

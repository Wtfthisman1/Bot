package Bot.account;

/**
 * Строка таблицы {@code bot_login_codes} — код входа через бота.
 *
 * <p>Живёт одну попытку входа: выдан сайту, подтверждён в чате, погашен
 * браузером. Каждое из трёх событий отмечено своим временем — по ним и
 * различаются «ждём», «можно входить» и «уже использован».</p>
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
@Table(name = "bot_login_codes")
@Getter
@Setter
@NoArgsConstructor
public class BotLoginCodeEntity {

    @Id
    private String code;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "chat_id")
    private Long chatId;

    @Column(name = "display_name")
    private String displayName;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "used_at")
    private Instant usedAt;

    /**
     * Двузначное число, которое видит страница входа.
     *
     * <p>Бот предлагает его среди трёх — совпасть должно то, что на экране.
     * Так подтверждение перестаёт быть просто нажатием кнопки: у того, кто
     * страницы не открывал, сверять не с чем.</p>
     */
    @Column(name = "check_number")
    private Integer checkNumber;
}

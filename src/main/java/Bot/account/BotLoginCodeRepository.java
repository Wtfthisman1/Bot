package Bot.account;

/**
 * Доступ к кодам входа через бота.
 */
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface BotLoginCodeRepository extends JpaRepository<BotLoginCodeEntity, String> {

    /** Просроченные коды не нужны никому: ни войти по ним, ни разобраться. */
    @Modifying
    @Query("delete from BotLoginCodeEntity c where c.expiresAt < :before")
    int deleteExpired(@Param("before") Instant before);
}

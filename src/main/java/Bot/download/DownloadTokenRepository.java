package Bot.download;

/**
 * Доступ к таблице выданных ссылок.
 */
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface DownloadTokenRepository extends JpaRepository<DownloadTokenEntity, String> {

    /** Выметает просроченные ссылки, чтобы таблица не росла без предела. */
    @Modifying
    @Query("delete from DownloadTokenEntity t where t.expiresAt < :now")
    int deleteExpired(@Param("now") Instant now);
}

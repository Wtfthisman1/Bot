package Bot.account;

/**
 * Доступ к одноразовым ссылкам входа через бота.
 */
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface BotLoginLinkRepository extends JpaRepository<BotLoginLinkEntity, String> {

    /**
     * Ссылка под блокировкой строки — для гашения.
     *
     * <p>Без неё «одноразовая» ссылка одноразова только на словах: два
     * одновременных нажатия читают {@code used_at = null} оба и входят оба.
     * Это и есть та единственная лазейка, ради которой ссылку пересылают —
     * жертва входит сама и ничего не замечает, а вторым проходит тот, кому она
     * её переслала. {@code for update} заставляет второго дождаться первого и
     * увидеть уже погашенную запись.</p>
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from BotLoginLinkEntity l where l.token = :token")
    java.util.Optional<BotLoginLinkEntity> lockByToken(@Param("token") String token);

    /** Просроченные ссылки не нужны никому: ни войти по ним, ни разобраться. */
    @Modifying
    @Query("delete from BotLoginLinkEntity l where l.expiresAt < :before")
    int deleteExpired(@Param("before") Instant before);

    /**
     * Сколько ссылок этот чат попросил за последнее время.
     *
     * <p>Нужно ограничителю: выдача ссылки — это запись в базу и сообщение в
     * чат, и повторять её без счёта нельзя.</p>
     */
    long countByChatIdAndCreatedAtGreaterThanEqual(Long chatId, Instant since);
}

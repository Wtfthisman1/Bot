package Bot.account;

/**
 * Доступ к таблице аккаунтов.
 */
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface AccountRepository extends JpaRepository<AccountEntity, UUID> {

    Optional<AccountEntity> findByEmail(String email);

    boolean existsByEmail(String email);

    /**
     * Строка аккаунта с блокировкой до конца транзакции.
     *
     * <p>Нужна не ради самих полей аккаунта, а как точка встречи: под этим
     * замком считаются задачи человека и ставится новая, поэтому двое
     * одновременно посчитать не могут. Пустой ответ (аккаунта нет) — не ошибка:
     * тогда и делить нечего.</p>
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from AccountEntity a where a.id = :id")
    Optional<AccountEntity> lockById(@Param("id") UUID id);
}

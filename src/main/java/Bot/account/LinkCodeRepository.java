package Bot.account;

/**
 * Доступ к кодам привязки.
 */
import org.springframework.data.jpa.repository.JpaRepository;

public interface LinkCodeRepository extends JpaRepository<LinkCodeEntity, String> {
}

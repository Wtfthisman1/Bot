package Bot.account;

/**
 * Доступ к внешним личностям аккаунта.
 */
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountIdentityRepository extends JpaRepository<AccountIdentityEntity, UUID> {

    Optional<AccountIdentityEntity> findByProviderAndProviderUserId(
            IdentityProvider provider, String providerUserId);

    List<AccountIdentityEntity> findByAccountId(UUID accountId);

    boolean existsByAccountIdAndProvider(UUID accountId, IdentityProvider provider);
}

package Bot.account;

/**
 * Аккаунты: регистрация, вход и всё, чем человек себя предъявляет.
 *
 * <p>Ответственность: завести аккаунт, не пустив второй на ту же почту или тот
 * же внешний вход, проверить пару «почта — пароль» и связать с аккаунтом
 * переписку в Telegram. Пароль нигде не хранится: в базу уходит только
 * BCrypt-хеш. Владелец задачи для аккаунта строится через
 * {@link Owner#account(String)}.</p>
 *
 * <p>Аккаунт может не иметь ни почты, ни пароля: вход через Telegram не даёт
 * ни того, ни другого. Поэтому «кто это» описывается не одной колонкой, а
 * набором личностей — {@link AccountIdentityEntity} плюс почта самого
 * аккаунта.</p>
 *
 * <p>Подтверждение почты сознательно не делается: оно требует внешнего
 * почтового сервиса, а свой почтовик на VPS уходит в спам. Регистрация
 * работает сразу, подтверждение добавится, когда будет выбран сервис.</p>
 */
import Bot.config.Profiles;
import Bot.owner.Owner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Profile(Profiles.HOME)
@Service
@RequiredArgsConstructor
@Slf4j
public class AccountService {

    /** Короче этого пароль не принимаем: подбор перебором становится реальным. */
    static final int MIN_PASSWORD_LENGTH = 8;

    /** Код привязки живёт минуты: его успевают переслать боту, но не подсмотреть. */
    private static final Duration LINK_CODE_TTL = Duration.ofMinutes(15);

    private static final String LINK_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int LINK_CODE_LENGTH = 6;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AccountRepository repository;
    private final AccountIdentityRepository identities;
    private final LinkCodeRepository linkCodes;

    /**
     * BCrypt: подбор хеша упирается в стоимость вычисления, а не в скорость
     * железа злоумышленника — в отличие от обычных хеш-функций.
     */
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    /* ───────── почта и пароль ───────── */

    /**
     * Заводит аккаунт.
     *
     * @throws EmailTakenException если почта уже занята
     * @throws IllegalArgumentException если пароль слишком короткий
     */
    @Transactional
    public Account register(String email, String rawPassword, String displayName) {
        String normalized = normalize(email);
        if (rawPassword == null || rawPassword.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException(
                    "Пароль должен быть не короче " + MIN_PASSWORD_LENGTH + " символов");
        }
        if (repository.existsByEmail(normalized)) {
            throw new EmailTakenException(normalized);
        }

        AccountEntity entity = newAccount(displayName);
        entity.setEmail(normalized);
        entity.setPasswordHash(passwordEncoder.encode(rawPassword));
        repository.save(entity);

        log.info("Зарегистрирован аккаунт: {}", normalized);
        return toAccount(entity);
    }

    /**
     * Проверяет пару «почта — пароль».
     *
     * <p>Неизвестная почта, аккаунт без пароля и неверный пароль дают одинаковый
     * пустой ответ: по разнице ответов подбирают список существующих адресов.</p>
     */
    @Transactional
    public Optional<Account> authenticate(String email, String rawPassword) {
        Optional<AccountEntity> found = repository.findByEmail(normalize(email));
        if (found.isEmpty() || rawPassword == null
                || found.get().getPasswordHash() == null
                || !passwordEncoder.matches(rawPassword, found.get().getPasswordHash())) {
            log.debug("Неудачная попытка входа: {}", normalize(email));
            return Optional.empty();
        }

        AccountEntity entity = found.get();
        entity.setLastLoginAt(Instant.now());
        return Optional.of(toAccount(entity));
    }

    @Transactional(readOnly = true)
    public Optional<Account> findByEmail(String email) {
        return repository.findByEmail(normalize(email)).map(AccountService::toAccount);
    }

    @Transactional(readOnly = true)
    public Optional<Account> findById(UUID id) {
        return repository.findById(id).map(AccountService::toAccount);
    }

    /* ───────── внешний вход ───────── */

    /**
     * Аккаунт по внешней личности; если такой ещё нет — заводит новый.
     *
     * <p>Именно так работает «войти через Google» и «войти через Telegram»:
     * отдельной регистрации у них нет, первый вход и есть регистрация.
     * Почта, если провайдер её отдал, подставляется только в пустое место и
     * только если ещё никем не занята: чужой адрес в своём аккаунте — это
     * захват чужих задач.</p>
     */
    @Transactional
    public Account findOrCreateByIdentity(IdentityProvider provider, String providerUserId,
                                          String displayName, String email) {
        Optional<AccountEntity> existing = identities
                .findByProviderAndProviderUserId(provider, providerUserId)
                .flatMap(identity -> repository.findById(identity.getAccountId()));

        if (existing.isPresent()) {
            AccountEntity entity = existing.get();
            entity.setLastLoginAt(Instant.now());
            // Имя берём у провайдера при каждом входе: человек меняет его у себя
            // в Telegram или Google и ждёт увидеть новое, а не то, что было
            // в день регистрации
            if (displayName != null && !displayName.isBlank()) {
                entity.setDisplayName(displayName);
            }
            return toAccount(entity);
        }

        AccountEntity entity = newAccount(displayName);
        if (email != null && !email.isBlank()) {
            String normalized = normalize(email);
            if (!repository.existsByEmail(normalized)) {
                entity.setEmail(normalized);
            }
        }
        entity.setLastLoginAt(Instant.now());
        repository.save(entity);
        saveIdentity(entity.getId(), provider, providerUserId);

        log.info("Заведён аккаунт по внешнему входу: провайдер={}, аккаунт={}", provider, entity.getId());
        return toAccount(entity);
    }

    /**
     * Привязывает внешнюю личность к существующему аккаунту.
     *
     * @throws IdentityTakenException если она уже ведёт в другой аккаунт
     */
    @Transactional
    public void linkIdentity(UUID accountId, IdentityProvider provider, String providerUserId) {
        Optional<AccountIdentityEntity> bound =
                identities.findByProviderAndProviderUserId(provider, providerUserId);
        if (bound.isPresent()) {
            if (bound.get().getAccountId().equals(accountId)) {
                return;   // уже привязано к этому же аккаунту — привязывать нечего
            }
            throw new IdentityTakenException(provider);
        }
        saveIdentity(accountId, provider, providerUserId);
        log.info("К аккаунту {} привязан вход {}", accountId, provider);
    }

    /**
     * Аккаунт, которому принадлежит переписка в Telegram.
     *
     * <p>Заводится молча при первом обращении: у бота нет страницы регистрации,
     * а квота и история должны работать и для тех, кто пришёл только в чат.
     * Тот же аккаунт человек получит, войдя на сайт через Telegram, — вход
     * ищется по тому же идентификатору.</p>
     */
    @Transactional
    public Account forTelegramChat(long chatId, String displayName) {
        return findOrCreateByIdentity(IdentityProvider.TELEGRAM, String.valueOf(chatId),
                displayName, null);
    }

    /* ───────── привязка чата к аккаунту ───────── */

    /** Выдаёт код, который человек пришлёт боту командой {@code /link}. */
    @Transactional
    public String issueLinkCode(UUID accountId) {
        StringBuilder code = new StringBuilder(LINK_CODE_LENGTH);
        for (int i = 0; i < LINK_CODE_LENGTH; i++) {
            code.append(LINK_CODE_ALPHABET.charAt(RANDOM.nextInt(LINK_CODE_ALPHABET.length())));
        }

        LinkCodeEntity entity = new LinkCodeEntity();
        entity.setCode(code.toString());
        entity.setAccountId(accountId);
        entity.setCreatedAt(Instant.now());
        entity.setExpiresAt(Instant.now().plus(LINK_CODE_TTL));
        linkCodes.save(entity);

        log.info("Выдан код привязки для аккаунта {}", accountId);
        return entity.getCode();
    }

    /**
     * Гасит код и привязывает к аккаунту чат.
     *
     * <p>Пустой ответ означает «код неизвестен, просрочен или уже сработал» —
     * все три случая для человека одинаковы, и по разнице ответов код было бы
     * можно подбирать.</p>
     */
    @Transactional
    public Optional<Account> redeemLinkCode(String code, long chatId) {
        Optional<LinkCodeEntity> found = linkCodes.findById(code == null ? "" : code.trim().toUpperCase(Locale.ROOT));
        if (found.isEmpty() || found.get().getUsedAt() != null
                || found.get().getExpiresAt().isBefore(Instant.now())) {
            return Optional.empty();
        }

        LinkCodeEntity entity = found.get();
        // Чат мог уже завести себе аккаунт молча — тогда старую привязку
        // снимаем: два аккаунта на одного человека и есть та беда, от которой
        // привязка спасает
        identities.findByProviderAndProviderUserId(IdentityProvider.TELEGRAM, String.valueOf(chatId))
                .ifPresent(identities::delete);
        identities.flush();
        saveIdentity(entity.getAccountId(), IdentityProvider.TELEGRAM, String.valueOf(chatId));

        entity.setUsedAt(Instant.now());
        log.info("Чат привязан к аккаунту {}", entity.getAccountId());
        return repository.findById(entity.getAccountId()).map(AccountService::toAccount);
    }

    /**
     * Все владельцы задач, которые принадлежат этому аккаунту.
     *
     * <p>Сам аккаунт плюс каждый привязанный чат: задачи из переписки остаются
     * записанными на чат — иначе результат некуда было бы отправить, — но в
     * истории и в квоте они принадлежат человеку, а не входу.</p>
     */
    @Transactional(readOnly = true)
    public List<Owner> ownersOf(UUID accountId) {
        List<Owner> owners = new ArrayList<>();
        owners.add(Owner.account(accountId.toString()));
        for (AccountIdentityEntity identity : identities.findByAccountId(accountId)) {
            if (identity.getProvider() == IdentityProvider.TELEGRAM) {
                owners.add(Owner.telegram(Long.parseLong(identity.getProviderUserId())));
            }
        }
        return owners;
    }

    /** Какими способами в этот аккаунт можно войти — для страницы профиля. */
    @Transactional(readOnly = true)
    public List<IdentityProvider> providersOf(UUID accountId) {
        return identities.findByAccountId(accountId).stream()
                .map(AccountIdentityEntity::getProvider)
                .distinct()
                .toList();
    }

    /* ───────── helpers ───────── */

    private AccountEntity newAccount(String displayName) {
        AccountEntity entity = new AccountEntity();
        entity.setId(UUID.randomUUID());
        entity.setDisplayName(displayName);
        entity.setCreatedAt(Instant.now());
        return entity;
    }

    private void saveIdentity(UUID accountId, IdentityProvider provider, String providerUserId) {
        AccountIdentityEntity identity = new AccountIdentityEntity();
        identity.setId(UUID.randomUUID());
        identity.setAccountId(accountId);
        identity.setProvider(provider);
        identity.setProviderUserId(providerUserId);
        identity.setCreatedAt(Instant.now());
        identities.save(identity);
    }

    /** Почта регистронезависима, и пробелы по краям — обычная опечатка при вводе. */
    private static String normalize(String email) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("Почта не может быть пустой");
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static Account toAccount(AccountEntity entity) {
        return new Account(entity.getId(), entity.getEmail(), entity.getDisplayName());
    }

    /** Аккаунт наружу — без всего, что связано с паролем. */
    public record Account(UUID id, String email, String displayName) {
        /** Владелец задач этого аккаунта. */
        public Owner asOwner() {
            return Owner.account(id.toString());
        }

        /** Как называть человека в интерфейсе, когда имени он не оставил. */
        public String title() {
            if (displayName != null && !displayName.isBlank()) {
                return displayName;
            }
            return email != null ? email : "Аккаунт";
        }
    }

    /** Почта занята. Отдельный тип, чтобы вызывающий мог показать понятный текст. */
    public static class EmailTakenException extends RuntimeException {
        public EmailTakenException(String email) {
            super("Аккаунт с почтой " + email + " уже существует");
        }
    }

    /** Внешний вход уже ведёт в другой аккаунт. */
    public static class IdentityTakenException extends RuntimeException {
        public IdentityTakenException(IdentityProvider provider) {
            super("Этот вход через " + provider + " уже привязан к другому аккаунту");
        }
    }
}

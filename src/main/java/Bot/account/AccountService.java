package Bot.account;

/**
 * Регистрация и проверка пароля.
 *
 * <p>Ответственность: завести аккаунт, не пустив второй с той же почтой, и
 * проверить пару «почта — пароль». Пароль нигде не хранится: в базу уходит
 * только BCrypt-хеш. Владелец задачи для такого аккаунта строится через
 * {@link Owner#account(String)}.</p>
 *
 * <p>Подтверждение почты сознательно не делается: оно требует внешнего
 * почтового сервиса, а свой почтовик на VPS уходит в спам. Регистрация
 * работает сразу, подтверждение добавится, когда будет выбран сервис.</p>
 */
import Bot.owner.Owner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AccountService {

    /** Короче этого пароль не принимаем: подбор перебором становится реальным. */
    static final int MIN_PASSWORD_LENGTH = 8;

    private final AccountRepository repository;

    /**
     * BCrypt: подбор хеша упирается в стоимость вычисления, а не в скорость
     * железа злоумышленника — в отличие от обычных хеш-функций.
     */
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

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

        AccountEntity entity = new AccountEntity();
        entity.setId(UUID.randomUUID());
        entity.setEmail(normalized);
        entity.setPasswordHash(passwordEncoder.encode(rawPassword));
        entity.setDisplayName(displayName);
        entity.setCreatedAt(Instant.now());
        repository.save(entity);

        log.info("Зарегистрирован аккаунт: {}", normalized);
        return toAccount(entity);
    }

    /**
     * Проверяет пару «почта — пароль».
     *
     * <p>Неизвестная почта и неверный пароль дают одинаковый пустой ответ:
     * по разнице ответов подбирают список существующих адресов.</p>
     */
    @Transactional
    public Optional<Account> authenticate(String email, String rawPassword) {
        Optional<AccountEntity> found = repository.findByEmail(normalize(email));
        if (found.isEmpty() || rawPassword == null
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

    /* ───────── helpers ───────── */

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
    }

    /** Почта занята. Отдельный тип, чтобы вызывающий мог показать понятный текст. */
    public static class EmailTakenException extends RuntimeException {
        public EmailTakenException(String email) {
            super("Аккаунт с почтой " + email + " уже существует");
        }
    }
}

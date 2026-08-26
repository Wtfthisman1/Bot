package Bot.account;

/**
 * Вход на сайт через бота — без номера телефона.
 *
 * <p>Ответственность: выдать одноразовый код, принять подтверждение из чата и
 * отдать странице аккаунт, когда подтверждение пришло. Три шага разнесены во
 * времени и приходят с разных сторон: код рождается в браузере, подтверждение —
 * в Telegram, а вход снова происходит в браузере.</p>
 *
 * <p>Почему не хватает самого {@code /start} со ссылки: ссылку можно прислать
 * постороннему, и нажатие «Запустить» пустило бы отправителя в чужой аккаунт.
 * Поэтому {@link #confirm} вызывается только после явного нажатия кнопки, где
 * назван домен, — а до тех пор код остаётся неподтверждённым.</p>
 *
 * <p>Аккаунт ищется по тому же идентификатору Telegram, что и вход виджетом,
 * так что оба способа ведут в одну и ту же учётную запись.</p>
 */
import Bot.config.Profiles;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

@Profile(Profiles.HOME)
@Service
@RequiredArgsConstructor
@Slf4j
public class BotLoginService {

    /**
     * Столько ждём подтверждения. Пять минут — это «переключился в Telegram и
     * нажал кнопку»; всё, что дольше, человек уже бросил, а код всё это время
     * лежит в открытой вкладке и в истории браузера.
     */
    private static final Duration TTL = Duration.ofMinutes(5);

    /** 16 байт — подобрать перебором за пять минут невозможно. */
    private static final int CODE_BYTES = 16;

    private static final SecureRandom RANDOM = new SecureRandom();
    /** Без padding: код едет в адресе ссылки t.me, где разрешены только буквы, цифры, «_» и «-». */
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final BotLoginCodeRepository codes;
    private final AccountService accounts;

    /** Заводит код и возвращает его странице ожидания. */
    @Transactional
    public String issue() {
        byte[] bytes = new byte[CODE_BYTES];
        RANDOM.nextBytes(bytes);

        BotLoginCodeEntity entity = new BotLoginCodeEntity();
        entity.setCode(ENCODER.encodeToString(bytes));
        entity.setCreatedAt(Instant.now());
        entity.setExpiresAt(Instant.now().plus(TTL));
        codes.save(entity);

        log.info("Выдан код входа через бота");
        return entity.getCode();
    }

    /**
     * Подтверждение из чата: этот человек действительно входит на сайт.
     *
     * @return {@code false}, если код неизвестен, просрочен или уже сработал —
     *         для бота это один и тот же ответ «код не подошёл»
     */
    @Transactional
    public boolean confirm(long chatId, String code, String displayName) {
        Optional<BotLoginCodeEntity> found = codes.findById(code == null ? "" : code.trim());
        if (found.isEmpty()) {
            log.info("Подтверждение входа с неизвестным кодом: chatId={}", chatId);
            return false;
        }

        BotLoginCodeEntity entity = found.get();
        if (entity.getUsedAt() != null || entity.getExpiresAt().isBefore(Instant.now())) {
            log.info("Подтверждение входа по просроченному коду: chatId={}", chatId);
            return false;
        }

        entity.setChatId(chatId);
        entity.setDisplayName(displayName);
        entity.setConfirmedAt(Instant.now());
        log.info("Вход через бота подтверждён: chatId={}", chatId);
        return true;
    }

    /** Что сейчас с кодом — на это смотрит страница ожидания. */
    @Transactional(readOnly = true)
    public State stateOf(String code) {
        Optional<BotLoginCodeEntity> found = codes.findById(code == null ? "" : code);
        if (found.isEmpty() || found.get().getUsedAt() != null) {
            return State.UNKNOWN;
        }
        BotLoginCodeEntity entity = found.get();
        if (entity.getConfirmedAt() != null) {
            return State.CONFIRMED;
        }
        return entity.getExpiresAt().isBefore(Instant.now()) ? State.EXPIRED : State.WAITING;
    }

    /**
     * Гасит подтверждённый код и отдаёт аккаунт, под которым входить.
     *
     * <p>Пусто — значит подтверждения ещё нет, оно устарело или код уже
     * сработал. Второй раз по тому же коду войти нельзя: он гасится здесь же,
     * в той же транзакции, что и поиск аккаунта.</p>
     */
    @Transactional
    public Optional<AccountService.Account> claim(String code) {
        Optional<BotLoginCodeEntity> found = codes.findById(code == null ? "" : code);
        if (found.isEmpty()) {
            return Optional.empty();
        }

        BotLoginCodeEntity entity = found.get();
        if (entity.getConfirmedAt() == null || entity.getUsedAt() != null
                || entity.getExpiresAt().isBefore(Instant.now())) {
            return Optional.empty();
        }

        entity.setUsedAt(Instant.now());
        return Optional.of(accounts.forTelegramChat(entity.getChatId(), entity.getDisplayName()));
    }

    /** Просроченные коды копятся зря: чистим раз в час. */
    @Scheduled(fixedRate = 60 * 60 * 1000)
    @Transactional
    public void purgeExpired() {
        int removed = codes.deleteExpired(Instant.now().minus(TTL));
        if (removed > 0) {
            log.debug("Убрано просроченных кодов входа: {}", removed);
        }
    }

    /** Состояние кода глазами страницы ожидания. */
    public enum State {
        /** Ждём, пока человек нажмёт кнопку в боте. */
        WAITING,
        /** Подтверждено — можно входить. */
        CONFIRMED,
        /** Время вышло: нужен новый код. */
        EXPIRED,
        /** Кода нет вовсе или он уже сработал. */
        UNKNOWN
    }
}

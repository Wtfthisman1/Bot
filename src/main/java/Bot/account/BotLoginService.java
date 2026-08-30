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
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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

    /** Сколько чисел показывает бот и в каких пределах они лежат. */
    private static final int CHOICES = 3;
    private static final int NUMBER_MIN = 10;
    private static final int NUMBER_BOUND = 90;   // 10..99

    /** Заводит код и число сверки и возвращает их странице ожидания. */
    @Transactional
    public Issued issue() {
        byte[] bytes = new byte[CODE_BYTES];
        RANDOM.nextBytes(bytes);

        BotLoginCodeEntity entity = new BotLoginCodeEntity();
        entity.setCode(ENCODER.encodeToString(bytes));
        entity.setCreatedAt(Instant.now());
        entity.setExpiresAt(Instant.now().plus(TTL));
        entity.setCheckNumber(NUMBER_MIN + RANDOM.nextInt(NUMBER_BOUND));
        codes.save(entity);

        log.info("Выдан код входа через бота");
        return new Issued(entity.getCode(), entity.getCheckNumber());
    }

    /**
     * Числа для кнопок в боте: настоящее и два посторонних, в случайном порядке.
     *
     * <p>Считает их дом, а не бот: бот не должен знать, какое из трёх верное —
     * он только показывает их и передаёт обратно выбранное.</p>
     *
     * <p>Пустой список — код неизвестен, просрочен или уже сработал. Для бота
     * это один и тот же ответ, чтобы по разнице нельзя было проверять коды.</p>
     */
    @Transactional(readOnly = true)
    public List<Integer> challengeFor(String code) {
        Optional<BotLoginCodeEntity> found = codes.findById(code == null ? "" : code.trim());
        if (found.isEmpty()) {
            return List.of();
        }
        BotLoginCodeEntity entity = found.get();
        if (entity.getUsedAt() != null || entity.getExpiresAt().isBefore(Instant.now())
                || entity.getCheckNumber() == null) {
            return List.of();
        }

        Set<Integer> numbers = new LinkedHashSet<>();
        numbers.add(entity.getCheckNumber());
        while (numbers.size() < CHOICES) {
            numbers.add(NUMBER_MIN + RANDOM.nextInt(NUMBER_BOUND));
        }

        List<Integer> shuffled = new ArrayList<>(numbers);
        Collections.shuffle(shuffled, RANDOM);
        return shuffled;
    }

    /** Код и число, которое страница покажет человеку. */
    public record Issued(String code, int checkNumber) {}

    /**
     * Подтверждение из чата: этот человек действительно входит на сайт.
     *
     * @return {@code false}, если код неизвестен, просрочен или уже сработал —
     *         для бота это один и тот же ответ «код не подошёл»
     */
    @Transactional
    public Confirmation confirm(long chatId, String code, String displayName, int chosenNumber) {
        Optional<BotLoginCodeEntity> found = codes.findById(code == null ? "" : code.trim());
        if (found.isEmpty()) {
            log.info("Подтверждение входа с неизвестным кодом: chatId={}", chatId);
            return Confirmation.STALE;
        }

        BotLoginCodeEntity entity = found.get();
        if (entity.getUsedAt() != null || entity.getExpiresAt().isBefore(Instant.now())) {
            log.info("Подтверждение входа по просроченному коду: chatId={}", chatId);
            return Confirmation.STALE;
        }

        // Не то число — код гасится немедленно. Ошибиться может и свой, но
        // куда вероятнее, что человеку прислали чужую ссылку и сверять ему не
        // с чем: дать вторую попытку значило бы дать её именно этому случаю
        if (entity.getCheckNumber() != null && entity.getCheckNumber() != chosenNumber) {
            entity.setUsedAt(Instant.now());
            log.warn("Вход через бота отклонён: число не совпало, код погашен (chatId={})", chatId);
            return Confirmation.WRONG_NUMBER;
        }

        entity.setChatId(chatId);
        entity.setDisplayName(displayName);
        entity.setConfirmedAt(Instant.now());
        log.info("Вход через бота подтверждён: chatId={}", chatId);
        return Confirmation.CONFIRMED;
    }

    /** Чем кончилось подтверждение — у каждого исхода свой текст в чате. */
    public enum Confirmation { CONFIRMED, WRONG_NUMBER, STALE }

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

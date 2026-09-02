package Bot.account;

/**
 * Вход на сайт через бота — без номера телефона и без пароля.
 *
 * <p>Ответственность: выдать чату одноразовую ссылку на сайт и пустить по ней
 * в аккаунт этого чата. Два шага, а не три: ссылка рождается в Telegram и там
 * же вручается, браузеру остаётся только её открыть.</p>
 *
 * <p><b>Почему направление именно такое.</b> Раньше вход начинала страница:
 * она заводила код, человек шёл с ним в бота и подтверждал вход, выбирая одно
 * из трёх чисел. Слабое место было не в числе, а в том, что начать вход мог
 * кто угодно, а подтверждать шли к чужому человеку — достаточно было прислать
 * ему ссылку под благовидным предлогом. Один к трём — не та вероятность,
 * которой стоит защищать чужую переписку.</p>
 *
 * <p>Теперь ссылку выдаёт бот тому, кто её попросил. Токен рождается из чата
 * человека и приходит только в этот чат, поэтому постороннему прислать нечего:
 * ссылки на чужой аккаунт не существует. Остаётся один случай — человек сам
 * перешлёт кому-то свою ссылку; на него работают короткий срок жизни,
 * одноразовость и сообщение в чат сразу после входа.</p>
 *
 * <p>Аккаунт ищется по тому же идентификатору Telegram, что и вход виджетом,
 * так что оба способа ведут в одну и ту же учётную запись.</p>
 */
import Bot.config.Profiles;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
     * Столько живёт ссылка. Три минуты — это «переключился в браузер и нажал»;
     * всё, что дольше, лежит в переписке и ждёт случая.
     */
    private static final Duration TTL = Duration.ofMinutes(3);

    /** 32 байта: подобрать перебором невозможно даже без ограничителя. */
    private static final int TOKEN_BYTES = 32;

    /**
     * Сколько ссылок один чат может попросить за окно.
     *
     * <p>Каждая — запись в базу и сообщение в чат. Живому человеку хватает
     * одной, второй-третьей он пользуется, когда первая протухла; всё, что
     * сверх, — это либо ошибка клиента, либо чужие руки.</p>
     */
    private static final int MAX_PER_WINDOW = 5;
    private static final Duration WINDOW = Duration.ofMinutes(10);

    private static final SecureRandom RANDOM = new SecureRandom();
    /** Без padding: токен едет в пути адреса, где «=» лишний. */
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    /** Публичный адрес сайта: из него собирается сама ссылка. */
    @Value("${site.base-url:http://localhost:8080}")
    private String baseUrl;

    private final BotLoginLinkRepository links;
    private final AccountService accounts;

    /**
     * Заводит ссылку входа для этого чата.
     *
     * <p>Пусто — чат просит их слишком часто; для бота это «попробуйте позже»,
     * и различать причины ему незачем.</p>
     */
    @Transactional
    public Optional<String> issue(long chatId, String displayName) {
        if (links.countByChatIdAndCreatedAtGreaterThanEqual(chatId, Instant.now().minus(WINDOW))
                >= MAX_PER_WINDOW) {
            log.warn("Слишком частые запросы ссылки входа: chatId={}", chatId);
            return Optional.empty();
        }

        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);

        BotLoginLinkEntity entity = new BotLoginLinkEntity();
        entity.setToken(ENCODER.encodeToString(bytes));
        entity.setChatId(chatId);
        entity.setDisplayName(displayName);
        entity.setCreatedAt(Instant.now());
        entity.setExpiresAt(Instant.now().plus(TTL));
        links.save(entity);

        log.info("Выдана ссылка входа на сайт: chatId={}", chatId);
        return Optional.of(site() + "/auth/enter/" + entity.getToken());
    }

    /**
     * Кого пустит эта ссылка — для страницы подтверждения.
     *
     * <p>Ничего не гасит намеренно: по ссылке ходят не только люди. Telegram
     * тянет предпросмотр, антивирусы и почтовые фильтры открывают адреса сами,
     * и гашение на GET сожгло бы вход до того, как человек его увидит.</p>
     */
    @Transactional(readOnly = true)
    public Optional<String> nameOf(String token) {
        return live(token).map(entity -> entity.getDisplayName() == null
                ? "" : entity.getDisplayName());
    }

    /**
     * Гасит ссылку и отдаёт аккаунт, под которым входить.
     *
     * <p>Пусто — ссылка неизвестна, просрочена или уже сработала. Строка берётся
     * под блокировку и гасится в той же транзакции, что и поиск аккаунта: двух
     * входов по одной ссылке не должно быть даже при двух одновременных
     * нажатиях — а именно на это и рассчитывает тот, кому ссылку переслали.</p>
     */
    @Transactional
    public Optional<Entry> claim(String token) {
        // Строка берётся под блокировку: два одновременных нажатия иначе
        // прочитали бы «не погашено» оба и вошли оба
        Optional<BotLoginLinkEntity> found = links.lockByToken(token == null ? "" : token.trim())
                .filter(entity -> entity.getUsedAt() == null)
                .filter(entity -> entity.getExpiresAt().isAfter(Instant.now()));
        if (found.isEmpty()) {
            return Optional.empty();
        }

        BotLoginLinkEntity entity = found.get();
        entity.setUsedAt(Instant.now());
        log.info("Вход по ссылке из бота: chatId={}", entity.getChatId());
        return Optional.of(new Entry(
                accounts.forTelegramChat(entity.getChatId(), entity.getDisplayName()),
                entity.getChatId()));
    }

    /** Кто вошёл и в какой чат сообщить об этом. */
    public record Entry(AccountService.Account account, long chatId) {}

    /** Просроченные ссылки копятся зря: чистим раз в час. */
    @Scheduled(fixedRate = 60 * 60 * 1000)
    @Transactional
    public void purgeExpired() {
        int removed = links.deleteExpired(Instant.now().minus(TTL));
        if (removed > 0) {
            log.debug("Убрано просроченных ссылок входа: {}", removed);
        }
    }

    /* ───────── helpers ───────── */

    /** Ссылка, по которой ещё можно войти: не погашена и не протухла. */
    private Optional<BotLoginLinkEntity> live(String token) {
        return links.findById(token == null ? "" : token.trim())
                .filter(entity -> entity.getUsedAt() == null)
                .filter(entity -> entity.getExpiresAt().isAfter(Instant.now()));
    }

    /** Хвостовой слэш дал бы адрес с двойным — часть прокси такое не маршрутизирует. */
    private String site() {
        String value = baseUrl == null ? "" : baseUrl.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }
}

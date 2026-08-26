package Bot.account;

/**
 * Сколько расшифровок человеку осталось в этом месяце.
 *
 * <p>Ответственность: посчитать израсходованное и сказать «да» или «нет» перед
 * постановкой задачи. Считается по аккаунту, а не по входу: у одного человека
 * бывает и сайт, и переписка с ботом, и лимит у них общий — иначе достаточно
 * уйти в другой вход, чтобы получить ещё три штуки.</p>
 *
 * <p>Чистое скачивание квоту не тратит: видеокарта на нём не работает, а
 * ограничение существует ради неё.</p>
 *
 * <p>Считается по записям в очереди, а не отдельным счётчиком: счётчик
 * пришлось бы чинить руками после каждой правки данных, а запросы по владельцу
 * и так покрыты индексом {@code jobs_owner_idx}.</p>
 */
import Bot.config.Profiles;
import Bot.owner.Owner;
import Bot.processing.JobRepository;
import Bot.processing.JobState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

@Profile(Profiles.HOME)
@Service
@RequiredArgsConstructor
@Slf4j
public class QuotaService {

    /** Сколько расшифровок в месяц бесплатно. */
    @Value("${quota.free-transcriptions-per-month:3}")
    private int freePerMonth;

    /**
     * Выключатель на случай «пока не считаем»: с ним бот работает без
     * ограничений, и для этого не нужно править код.
     */
    @Value("${quota.enabled:true}")
    private boolean enabled;

    /**
     * Часовой пояс, по которому месяц считается начавшимся. Без него граница
     * месяца ехала бы по UTC, и первого числа утром лимит ещё не обновлялся бы.
     */
    @Value("${quota.zone:Europe/Moscow}")
    private String zoneId;

    private final JobRepository jobs;
    private final AccountService accounts;

    /** Остаток квоты аккаунта. */
    @Transactional
    public Quota of(UUID accountId) {
        if (!enabled) {
            return Quota.disabled();
        }
        Instant since = monthStart();
        long used = 0;
        for (Owner owner : accounts.ownersOf(accountId)) {
            used += jobs.countByOwnerTypeAndOwnerIdAndDownloadIdIsNullAndStateNotAndCreatedAtGreaterThanEqual(
                    owner.type(), owner.id(), JobState.FAILED, since);
        }
        return new Quota(freePerMonth, used, false);
    }

    /**
     * Можно ли поставить ещё одну расшифровку этому владельцу.
     *
     * <p>Владелец разворачивается в аккаунт: у чата, который ещё нигде не
     * зарегистрирован, аккаунт заводится на месте — иначе переписка обходила бы
     * ограничение просто потому, что пришла раньше сайта.</p>
     */
    @Transactional
    public boolean allows(Owner owner) {
        if (!enabled) {
            return true;
        }
        Quota quota = of(accountIdOf(owner));
        if (quota.exceeded()) {
            log.info("Квота исчерпана: владелец={}, израсходовано={}/{}",
                    owner, quota.used(), quota.limit());
            return false;
        }
        return true;
    }

    /** То же самое, но по владельцу задачи, — для ответа боту. */
    @Transactional
    public Quota forOwner(Owner owner) {
        return enabled ? of(accountIdOf(owner)) : Quota.disabled();
    }

    /* ───────── helpers ───────── */

    private UUID accountIdOf(Owner owner) {
        if (owner.isTelegram()) {
            return accounts.forTelegramChat(owner.telegramChatId(), null).id();
        }
        return UUID.fromString(owner.id());
    }

    private Instant monthStart() {
        ZoneId zone = ZoneId.of(zoneId);
        return LocalDate.now(zone).withDayOfMonth(1).atStartOfDay(zone).toInstant();
    }

    /** Сколько всего, сколько потрачено — и хватит ли на ещё одну задачу. */
    public record Quota(int limit, long used, boolean unlimited) {

        /** Квота выключена настройкой — считать нечего. */
        public static Quota disabled() {
            return new Quota(0, 0, true);
        }

        public long remaining() {
            return unlimited ? Long.MAX_VALUE : Math.max(0, limit - used);
        }

        public boolean exceeded() {
            return !unlimited && used >= limit;
        }

        /** Строка для кабинета и для бота: «осталось 2 из 3 в этом месяце». */
        public String describe() {
            return unlimited ? "без ограничений"
                    : "осталось " + remaining() + " из " + limit + " в этом месяце";
        }
    }
}

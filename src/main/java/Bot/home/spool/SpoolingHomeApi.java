package Bot.home.spool;

/**
 * Приём задач при спящем доме.
 *
 * <p>Ответственность: не терять то, что человек прислал, пока домашняя машина
 * выключена. Обёртка над обычным клиентом: дом отвечает — работаем как всегда,
 * молчит — задача ложится в {@link TaskSpool} и уходит при первом же
 * пробуждении. Пользователю при этом говорят правду («задача принята,
 * запустится, когда машина проснётся»), а не привычное «всё запущено».</p>
 *
 * <p>Пока спул не пуст, новые задачи тоже кладутся в него, даже если дом уже
 * ответил: иначе присланное позже обгоняло бы то, что ждёт с ночи.</p>
 *
 * <p>Отложить можно не всё. Ссылка на форму загрузки и выдача расшифровки
 * бессмысленны без дома: форму отдаёт он, файлы лежат у него. Эти вызовы
 * проваливаются как раньше, и пользователь видит «недоступно».</p>
 */
import Bot.home.HomeApi;
import Bot.home.HomeUnavailableException;
import Bot.owner.Owner;
import Bot.processing.MediaKind;
import Bot.telegram.Keyboards;
import Bot.telegram.MessageSender;
import Bot.transcription.TranscriptFormat;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
public class SpoolingHomeApi implements HomeApi {

    /**
     * Сколько раз повторять задачу, на которую дом ответил ошибкой.
     * Молчание выключенной машины сюда не входит — это не попытка.
     */
    private static final int MAX_ATTEMPTS = 5;

    private final HomeApi delegate;
    private final TaskSpool spool;
    private final MessageSender messageSender;
    private final int capacity;
    private final Duration maxAge;

    /** Отправка и приём не должны толкаться в спуле: порядок задач важнее скорости. */
    private final ReentrantLock lock = new ReentrantLock();

    public SpoolingHomeApi(HomeApi delegate, TaskSpool spool, MessageSender messageSender,
                           int capacity, Duration maxAge) {
        this.delegate = delegate;
        this.spool = spool;
        this.messageSender = messageSender;
        this.capacity = capacity;
        this.maxAge = maxAge;
    }

    /* ───────── приём ───────── */

    @Override
    public Acceptance transcribeLink(Owner owner, String url) {
        return unchecked(() -> accept(SpooledTask.transcribeLink(owner, url)));
    }

    @Override
    public Acceptance downloadLink(Owner owner, String url, MediaKind media) {
        return unchecked(() -> accept(SpooledTask.downloadLink(owner, url, media)));
    }

    @Override
    public Acceptance transcribeTelegramFile(Owner owner, TelegramFile file) throws Exception {
        return accept(SpooledTask.telegramFile(owner, file));
    }

    /**
     * Дом недоступен — откладываем; ответил ошибкой — отдаём её вызывающему.
     *
     * <p>Разница принципиальна: «файл слишком большой» не станет верным через
     * час, и повторять его бессмысленно, а выключенная машина включится.</p>
     */
    private Acceptance accept(SpooledTask task) throws Exception {
        lock.lock();
        try {
            if (spool.size() == 0) {
                try {
                    // Дом мог и отказать — например, кончилась квота. Это ответ,
                    // а не сбой: откладывать такую задачу незачем
                    return send(task);
                } catch (HomeUnavailableException e) {
                    log.info("Дом не ответил, задача уходит в спул: {}", task.describe());
                }
            }

            if (spool.size() >= capacity) {
                // Бесконечно копить нельзя: диск VPS — 10 ГБ, а сама очередь
                // такого размера означает, что дом не включался очень давно
                throw new HomeUnavailableException(
                        "Спул переполнен: " + spool.size() + " задач ждут дома", null);
            }
            spool.add(task);
            return Acceptance.DEFERRED;
        } finally {
            lock.unlock();
        }
    }

    /* ───────── передача домой ───────── */

    /**
     * Отдаёт домой то, что накопилось.
     *
     * <p>Первая же неудача по связи прекращает обход: дом либо доступен, либо
     * нет, и долбиться остальными задачами незачем.</p>
     */
    @Scheduled(fixedDelayString = "${spool.flush-interval-ms:60000}")
    public void flush() {
        // Не ждём в очереди на замок: если приём занят, следующий тик наш
        if (!lock.tryLock()) {
            return;
        }
        try {
            dropExpired();

            List<SpooledTask> pending = spool.pending();
            if (pending.isEmpty()) {
                return;
            }

            log.info("Пробую отдать домой отложенные задачи: {}", pending.size());
            Set<Owner> resumed = new LinkedHashSet<>();
            for (SpooledTask task : pending) {
                try {
                    Acceptance answer = send(task);
                    spool.remove(task);
                    if (answer.isRejected()) {
                        // Пока задача лежала в спуле, лимит успел кончиться —
                        // молча выбрасывать её нельзя, человек её ждёт
                        tell(task.owner(), answer.userMessage());
                        continue;
                    }
                    resumed.add(task.owner());
                } catch (HomeUnavailableException e) {
                    log.debug("Дом всё ещё недоступен, ждём следующего раза");
                    break;
                } catch (Exception e) {
                    giveUpOrRetry(task, e);
                }
            }
            announceResumed(resumed);
        } finally {
            lock.unlock();
        }
    }

    private Acceptance send(SpooledTask task) throws Exception {
        return switch (task.kind()) {
            case TRANSCRIBE_LINK -> delegate.transcribeLink(task.owner(), task.url());
            case DOWNLOAD_LINK -> delegate.downloadLink(task.owner(), task.url(), task.media());
            case TELEGRAM_FILE -> delegate.transcribeTelegramFile(task.owner(), task.file());
        };
    }

    /** Дом ответил ошибкой: пробуем ещё несколько раз, потом честно сдаёмся. */
    private void giveUpOrRetry(SpooledTask task, Exception cause) {
        SpooledTask attempted = task.afterFailedAttempt();
        if (attempted.attempts() >= MAX_ATTEMPTS) {
            log.error("Задача из спула не удалась {} раз, отказываюсь: {}",
                    attempted.attempts(), task.describe(), cause);
            spool.remove(task);
            tell(task.owner(), "❌ Не получилось запустить задачу: " + task.describe());
            return;
        }
        log.warn("Дом ответил ошибкой на задачу из спула (попытка {}): {}",
                attempted.attempts(), task.describe(), cause);
        spool.replace(attempted);
    }

    /** Задачу недельной давности повторять поздно — человек про неё забыл. */
    private void dropExpired() {
        for (SpooledTask task : spool.olderThan(maxAge)) {
            log.warn("Задача протухла в спуле, убираю: {}", task.describe());
            spool.remove(task);
            tell(task.owner(), "🕓 Задача ждала слишком долго и снята: " + task.describe()
                    + "\n\nПришлите её снова, если она ещё нужна.");
        }
    }

    private void announceResumed(Set<Owner> owners) {
        for (Owner owner : owners) {
            tell(owner, "▶️ Рабочая машина проснулась — принятые задачи пошли в работу.");
        }
    }

    /* ───────── остальное — как есть ───────── */

    @Override
    public String uploadFormLink(Owner owner) {
        return delegate.uploadFormLink(owner);
    }

    /**
     * К сводке дома добавляются отложенные задачи.
     *
     * <p>Дом о них не знает, но для пользователя они ничем не отличаются от
     * стоящих в очереди: он их прислал, и они ждут. Молчащий дом даёт сводку
     * из одного спула — это лучше, чем ошибка на ровном месте.</p>
     */
    @Override
    public OwnerStatus status(Owner owner) {
        long deferred = spool.countFor(owner);
        try {
            OwnerStatus home = delegate.status(owner);
            return new OwnerStatus(home.queued() + deferred, home.running(), home.downloads());
        } catch (HomeUnavailableException e) {
            return new OwnerStatus(deferred, 0, List.of());
        }
    }

    @Override
    public void sendTranscript(Owner owner, String jobId, TranscriptFormat format) {
        delegate.sendTranscript(owner, jobId, format);
    }

    /**
     * Откладывать нечего: выжимку считает дом по расшифровке, которая у него же
     * и лежит. При спящей машине человек услышит «недоступно» — как и с
     * ссылкой на форму загрузки.
     */
    @Override
    public java.util.Optional<String> summarize(Owner owner, String jobId) {
        return delegate.summarize(owner, jobId);
    }

    @Override
    public java.util.Optional<String> linkTelegram(long chatId, String code) {
        return delegate.linkTelegram(chatId, code);
    }

    @Override
    public boolean confirmBotLogin(long chatId, String code, String displayName) {
        return delegate.confirmBotLogin(chatId, code, displayName);
    }

    /* ───────── helpers ───────── */

    /** У аккаунта сайта чата нет; ему о судьбе задачи расскажет сам сайт. */
    private void tell(Owner owner, String text) {
        if (!owner.isTelegram()) {
            log.info("Владельцу {} сообщить некуда: {}", owner, text);
            return;
        }
        messageSender.sendMessageWithKeyboard(owner.telegramChatId(), text, null,
                Keyboards.mainMenu());
    }

    /** Постановка ссылки проверяемых исключений не бросает — но компилятор об этом не знает. */
    private Acceptance unchecked(Callable<Acceptance> action) {
        try {
            return action.call();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Неожиданная ошибка постановки задачи", e);
        }
    }
}

package Bot.processing;

/**
 * Впустить задачу в очередь — проверив, что человеку ещё можно.
 *
 * <p>Ответственность одна: сделать «посчитал и поставил» неделимым. Проверки
 * те же, что были и раньше, — предел незавершённых задач, место на диске и
 * месячная квота, — но раньше каждая из них жила отдельным вызовом до
 * {@code jobStore.enqueue}, и между ними помещался чужой запрос: два
 * одновременных нажатия на последней доступной расшифровке читали «использовано
 * 2 из 3» оба и оба ставили задачу. Ошибка на единицу, но с бесплатной квотой
 * из трёх штук это лишняя треть, а с формой на пять файлов — лишние пять.</p>
 *
 * <p>Как чинится: первым делом берётся замок на строку аккаунта
 * ({@link AccountService#lockForAdmission}), и до конца транзакции второй
 * запрос того же человека ждёт. Дождавшись, он считает уже вместе с чужой
 * задачей и получает честный отказ. Разные люди друг друга не задерживают:
 * замок берётся на свою строку.</p>
 *
 * <p>Отсюда следует порядок работы для вызывающих: сначала дешёвая проверка
 * («не занимать канал скачиванием файла, который всё равно не примут»), потом
 * сама работа, и только затем {@code admit} — то есть отказ может прийти уже
 * после того, как файл сохранён. Файл в этом случае удаляется вызывающим.</p>
 */
import Bot.account.AccountService;
import Bot.account.QuotaService;
import Bot.config.Profiles;
import Bot.owner.Owner;
import Bot.service.StorageManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Profile(Profiles.HOME)
@Service
@RequiredArgsConstructor
@Slf4j
public class JobAdmission {

    private final JobStore jobs;
    private final QuotaService quotas;
    private final StorageManager storage;
    private final AccountService accounts;

    /** Чем закончилась попытка. Ответ пользователю каждый вызывающий даёт свой. */
    public enum Verdict {
        ACCEPTED, TOO_MANY_ACTIVE, NO_ROOM, QUOTA_EXCEEDED;

        public boolean accepted() {
            return this == ACCEPTED;
        }
    }

    /**
     * Пустить расшифровку в очередь — или сказать, почему нет.
     *
     * <p>Размер будущего файла отдельным доводом не идёт: к этому моменту файл
     * уже лежит в каталоге владельца, и {@code hasRoom} считает занятое вместе
     * с ним. Прибавить сверху ещё и его размер значило бы посчитать его
     * дважды и отказать тому, кому места хватает.</p>
     */
    @Transactional
    public Verdict admit(ProcessingJob job) {
        Owner owner = job.owner();
        accounts.lockForAdmission(owner);

        if (jobs.tooManyActive(owner)) {
            return Verdict.TOO_MANY_ACTIVE;
        }
        if (!storage.hasRoom(owner)) {
            return Verdict.NO_ROOM;
        }
        if (!quotas.allows(owner)) {
            return Verdict.QUOTA_EXCEEDED;
        }
        jobs.enqueue(job);
        return Verdict.ACCEPTED;
    }

    /**
     * Скачивание: квоту оно не тратит (видеокарта на нём не работает), но
     * очередь и диск занимает так же, как расшифровка.
     *
     * @param enqueue что сделать, если пускаем; выполняется под тем же замком
     */
    @Transactional
    public Verdict admitDownload(Owner owner, Runnable enqueue) {
        accounts.lockForAdmission(owner);

        if (jobs.tooManyActive(owner)) {
            return Verdict.TOO_MANY_ACTIVE;
        }
        if (!storage.hasRoom(owner)) {
            return Verdict.NO_ROOM;
        }
        enqueue.run();
        return Verdict.ACCEPTED;
    }
}

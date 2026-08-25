package Bot.notify;

/**
 * Куда отдавать результат задачи.
 *
 * <p>Ответственность: развязать воркер и Telegram. Раньше воркер звал
 * {@code MessageSender} напрямую, и потому умел работать только с чатом.
 * У аккаунта на сайте чата нет — результат ему кладут в базу, а страница
 * его показывает; это будет вторая реализация этого же интерфейса.</p>
 *
 * <p>Реализации выбираются по {@link Owner} через {@link JobNotifiers}.</p>
 */
import Bot.owner.Owner;

import java.nio.file.Path;

public interface JobNotifier {

    /** Умеет ли эта реализация обслуживать такого владельца. */
    boolean supports(Owner owner);

    /** Расшифровка готова — отдать её владельцу. */
    void transcriptReady(Owner owner, Path txt);

    /** Задача сорвалась: {@code message} уже написан на языке пользователя. */
    void failed(Owner owner, String message);
}

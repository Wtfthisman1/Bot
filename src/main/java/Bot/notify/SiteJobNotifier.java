package Bot.notify;

/**
 * Доставка результата аккаунту сайта.
 *
 * <p>Отправлять некуда и не нужно: расшифровка и текст ошибки уже записаны в
 * задаче, а кабинет показывает их вместе с самой задачей. Класс существует
 * ради того, чтобы {@link JobNotifiers} не считал такого владельца ошибкой
 * настройки и не писал в журнал «некому доставить результат» на каждой
 * задаче с сайта.</p>
 *
 * <p>Здесь же появится письмо на почту, когда будет выбран почтовый сервис.</p>
 */
import Bot.config.Profiles;
import Bot.owner.Owner;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.UUID;

@Profile(Profiles.HOME)
@Component
@Slf4j
public class SiteJobNotifier implements JobNotifier {

    @Override
    public boolean supports(Owner owner) {
        return !owner.isTelegram();
    }

    @Override
    public void transcriptReady(UUID jobId, Owner owner, Path txt) {
        log.info("Расшифровка готова и ждёт в кабинете: аккаунт={}, jobId={}", owner.id(), jobId);
    }

    @Override
    public void failed(Owner owner, String message) {
        log.info("Задача сорвалась, причина видна в кабинете: аккаунт={}", owner.id());
    }
}

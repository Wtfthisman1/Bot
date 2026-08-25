package Bot.notify;

/**
 * Выбор способа доставки по владельцу задачи.
 *
 * <p>Ответственность: найти подходящий {@link JobNotifier} и не дать результату
 * потеряться молча. Владелец без обработчика — это ошибка конфигурации, и она
 * должна быть видна в логе: пользователь иначе просто не дождётся расшифровки,
 * не получив ни файла, ни объяснения.</p>
 */
import Bot.owner.Owner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class JobNotifiers {

    private final List<JobNotifier> notifiers;

    public void transcriptReady(UUID jobId, Owner owner, Path txt) {
        find(owner).ifPresent(n -> n.transcriptReady(jobId, owner, txt));
    }

    public void failed(Owner owner, String message) {
        find(owner).ifPresent(n -> n.failed(owner, message));
    }

    private Optional<JobNotifier> find(Owner owner) {
        Optional<JobNotifier> notifier = notifiers.stream()
                .filter(n -> n.supports(owner))
                .findFirst();
        if (notifier.isEmpty()) {
            log.error("Некому доставить результат владельцу {} — результат останется только в базе", owner);
        }
        return notifier;
    }
}

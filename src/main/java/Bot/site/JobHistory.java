package Bot.site;

/**
 * История задач для страницы «мои задачи».
 *
 * <p>Ответственность: собрать все задачи аккаунта — и поставленные на сайте, и
 * присланные в чат — в один список, годный для показа. Задачи из переписки
 * по-прежнему записаны на чат: результат надо куда-то отправлять, а у аккаунта
 * чата нет. Поэтому список склеивается из нескольких владельцев, которых даёт
 * {@link AccountService#ownersOf}.</p>
 *
 * <p>Проверка «моё ли это» живёт здесь же: страница отдаёт файлы по номеру
 * задачи, и чужой номер должен выглядеть точно так же, как несуществующий.</p>
 */
import Bot.account.AccountService;
import Bot.config.Profiles;
import Bot.owner.Owner;
import Bot.processing.JobEntity;
import Bot.processing.JobRepository;
import Bot.processing.JobState;
import Bot.processing.ProcessingJob;
import Bot.transcription.TranscriptFormat;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Profile(Profiles.HOME)
@Service
@RequiredArgsConstructor
public class JobHistory {

    /** Сколько задач показываем: дальше человек всё равно не листает. */
    private static final int PAGE = 50;

    /**
     * Время в строке истории готовится здесь, а не в шаблоне: в шаблоне пришлось
     * бы полагаться на диалект для java.time, а формат даты — не то, ради чего
     * стоит держать лишнюю зависимость.
     */
    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault());

    private final JobRepository jobs;
    private final AccountService accounts;

    @Transactional(readOnly = true)
    public List<Entry> of(UUID accountId) {
        List<JobEntity> all = new ArrayList<>();
        for (Owner owner : accounts.ownersOf(accountId)) {
            all.addAll(jobs.findTop200ByOwnerTypeAndOwnerIdOrderByCreatedAtDesc(
                    owner.type(), owner.id()));
        }
        return all.stream()
                .sorted(Comparator.comparing(JobEntity::getCreatedAt).reversed())
                .limit(PAGE)
                .map(JobHistory::toEntry)
                .toList();
    }

    /**
     * Все задачи аккаунта: номер и подпись.
     *
     * <p>Нужно поиску: он ищет по репликам, но показывать их надо вместе с тем,
     * из какой они записи. Ограничения в 50 строк здесь нет — это не список
     * для чтения, а область, в которой разрешено искать.</p>
     */
    @Transactional(readOnly = true)
    public Map<UUID, String> titlesOf(UUID accountId) {
        Map<UUID, String> titles = new LinkedHashMap<>();
        for (Owner owner : accounts.ownersOf(accountId)) {
            jobs.findTop200ByOwnerTypeAndOwnerIdOrderByCreatedAtDesc(owner.type(), owner.id())
                    .forEach(job -> titles.put(job.getId(), title(job)));
        }
        return titles;
    }

    /** Задача аккаунта по её номеру; чужая и несуществующая — одинаково пусто. */
    @Transactional(readOnly = true)
    public Optional<JobEntity> find(UUID accountId, UUID jobId) {
        List<Owner> owners = accounts.ownersOf(accountId);
        return jobs.findById(jobId).filter(job -> owners.contains(job.owner()));
    }

    /* ───────── helpers ───────── */

    private static Entry toEntry(JobEntity job) {
        Path transcript = job.getTranscriptPath() == null ? null : Path.of(job.getTranscriptPath());
        Path media = job.getFilePath() == null ? null : Path.of(job.getFilePath());

        return new Entry(
                job.getId().toString(),
                title(job),
                job.getDownloadId() != null ? "Скачивание" : "Расшифровка",
                stateText(job),
                job.getState() == JobState.FAILED,
                WHEN.format(job.getCreatedAt()),
                job.owner().isTelegram(),
                availableFormats(transcript),
                media != null && Files.isRegularFile(media) ? media.getFileName().toString() : null,
                job.getError());
    }

    /** Чем задача была: ссылкой или присланным файлом. */
    private static String title(JobEntity job) {
        if (job.getUrl() != null) {
            return job.getUrl();
        }
        if (job.getFilePath() != null) {
            return Path.of(job.getFilePath()).getFileName().toString();
        }
        return "Задача " + job.getId().toString().substring(0, 8);
    }

    private static String stateText(JobEntity job) {
        return switch (job.getState()) {
            case QUEUED -> "В очереди";
            case RUNNING -> job.getStage() == ProcessingJob.Stage.DOWNLOAD
                    ? "Скачивается" : "Расшифровывается";
            case DONE -> "Готово";
            case FAILED -> "Не получилось";
        };
    }

    /**
     * Форматы, которые правда лежат на диске.
     *
     * <p>Проверяется файловая система, а не запись в базе: файлы старше двух
     * недель уносит ночная чистка, и кнопка на исчезнувший файл — обман.</p>
     */
    private static List<String> availableFormats(Path transcript) {
        if (transcript == null || !Files.isRegularFile(transcript)) {
            return List.of();
        }
        List<String> formats = new ArrayList<>();
        for (TranscriptFormat format : TranscriptFormat.values()) {
            // Word собирается из текста по требованию, поэтому доступен всегда,
            // когда есть сам текст
            if (format == TranscriptFormat.DOCX || Files.isRegularFile(format.fileFor(transcript))) {
                formats.add(format.code());
            }
        }
        return formats;
    }

    /** Строка истории — ровно то, что показывает страница. */
    public record Entry(String id,
                        String title,
                        String kind,
                        String state,
                        boolean failed,
                        String when,
                        boolean fromTelegram,
                        List<String> formats,
                        String mediaName,
                        String error) {}
}

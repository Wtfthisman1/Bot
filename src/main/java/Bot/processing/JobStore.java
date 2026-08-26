package Bot.processing;

/**
 * Очередь задач поверх базы.
 *
 * <p>Ответственность: поставить задачу, выдать её свободному воркеру, отметить
 * результат и вернуть в очередь то, что застряло. Заменяет очередь в памяти:
 * та теряла всё при перезапуске — пользователь ждал расшифровку, которой уже
 * никто не занимался.</p>
 *
 * <p>Воркеры забирают задачи через {@code claim}: строка блокируется
 * {@code FOR UPDATE SKIP LOCKED}, поэтому одну и ту же задачу двое взять
 * не могут, и никто никого не ждёт. Связан с {@link JobRepository};
 * используется {@link JobWorker} и всеми, кто ставит задачи.</p>
 */
import Bot.config.Profiles;
import Bot.owner.Owner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Profile(Profiles.HOME)
@Service
@RequiredArgsConstructor
@Slf4j
public class JobStore {

    /** Незавершённые состояния — то, что пользователь считает «в работе». */
    private static final List<JobState> UNFINISHED = List.of(JobState.QUEUED, JobState.RUNNING);

    private final JobRepository repository;

    /** Ставит задачу в очередь. */
    @Transactional
    public ProcessingJob enqueue(ProcessingJob job) {
        Instant now = Instant.now();
        JobEntity entity = new JobEntity();
        entity.setId(job.id());
        entity.setOwnerType(job.owner().type());
        entity.setOwnerId(job.owner().id());
        entity.setStage(job.stage());
        entity.setState(JobState.QUEUED);
        entity.setUrl(job.url());
        entity.setMediaKind(job.media());
        entity.setFilePath(job.filePath() == null ? null : job.filePath().toString());
        entity.setDownloadId(job.downloadId());
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        repository.save(entity);

        log.info("Задача поставлена в очередь: jobId={}, владелец={}, этап={}, в очереди={}",
                job.shortId(), job.owner(), job.stage(), queued());
        return job;
    }

    /**
     * Забирает следующую задачу и помечает её как выполняемую.
     *
     * <p>Пустой результат означает «очередь пуста» — это нормальный ответ,
     * а не ошибка: воркер просто подождёт и спросит снова.</p>
     */
    @Transactional
    public Optional<ProcessingJob> claim() {
        return repository.lockNextQueued()
                .flatMap(repository::findById)
                .map(entity -> {
                    entity.setState(JobState.RUNNING);
                    entity.setAttempts(entity.getAttempts() + 1);
                    entity.setStartedAt(Instant.now());
                    entity.setUpdatedAt(Instant.now());
                    ProcessingJob job = toJob(entity);
                    log.debug("Задача взята в работу: jobId={}, попытка={}",
                            job.shortId(), entity.getAttempts());
                    return job;
                });
    }

    /**
     * Скачивание закончено — та же задача возвращается в очередь на расшифровку.
     *
     * <p>Именно та же строка, а не новая: иначе история задачи распалась бы
     * на две записи, и «сколько раз это скачивали» перестало бы считаться.</p>
     */
    @Transactional
    public void moveToTranscribe(ProcessingJob job) {
        JobEntity entity = require(job.id());
        entity.setStage(ProcessingJob.Stage.TRANSCRIBE);
        entity.setState(JobState.QUEUED);
        entity.setFilePath(job.filePath().toString());
        entity.setStartedAt(null);
        entity.setUpdatedAt(Instant.now());
        log.debug("Задача переведена на расшифровку: jobId={}", job.shortId());
    }

    /**
     * Задача отработала. {@code result} — расшифровка или скачанный файл.
     *
     * <p>Путь запоминается в обоих случаях: до появления сайта скачанный файл
     * уходил ссылкой сразу и больше был не нужен, а странице «мои задачи» его
     * надо показать и через день — хотя бы затем, чтобы выдать ссылку заново.</p>
     */
    @Transactional
    public void complete(UUID id, Path result) {
        JobEntity entity = require(id);
        entity.setState(JobState.DONE);
        if (result != null && entity.getStage() == ProcessingJob.Stage.TRANSCRIBE) {
            entity.setTranscriptPath(result.toString());
        } else if (result != null) {
            entity.setFilePath(result.toString());
        }
        entity.setFinishedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());
    }

    /** Задача сорвалась. Текст ошибки — тот же, что ушёл пользователю. */
    @Transactional
    public void fail(UUID id, String error) {
        JobEntity entity = require(id);
        entity.setState(JobState.FAILED);
        entity.setError(error);
        entity.setFinishedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());
        log.debug("Задача помечена сорвавшейся: jobId={}", id.toString().substring(0, 8));
    }

    /**
     * Сведения о задаче скачивания.
     *
     * <p>Раньше они лежали в карте внутри {@code DownloadService} и умирали
     * вместе с процессом. После переезда очереди в базу это стало заметно:
     * задача перезапуск переживала, а данные о ней — нет, и скачанный файл
     * молча пропадал. Всё нужное и так есть в строке задачи.</p>
     */
    @Transactional(readOnly = true)
    public Optional<DownloadJob> findDownload(String downloadId) {
        return repository.findByDownloadId(downloadId).map(JobStore::toDownload);
    }

    /**
     * Путь к расшифровке по задаче — если она принадлежит этому владельцу.
     *
     * <p>По нему кнопки под расшифровкой находят соседние форматы. Отдельного
     * реестра для этого не нужно: задача и так знает, что получилось.
     * Пустой результат означает «чужая задача, нет такой или файл ещё
     * не готов» — все три случая для пользователя выглядят одинаково.</p>
     */
    @Transactional(readOnly = true)
    public Optional<Path> transcriptOf(UUID jobId, Owner owner) {
        return repository.findById(jobId)
                .filter(entity -> entity.owner().equals(owner))
                .map(JobEntity::getTranscriptPath)
                .map(Path::of);
    }

    /** Незавершённые загрузки владельца — для ответа на «Статус». */
    @Transactional(readOnly = true)
    public List<DownloadJob> activeDownloads(Owner owner) {
        return repository
                .findByOwnerTypeAndOwnerIdAndDownloadIdIsNotNullAndStateInOrderByCreatedAtDesc(
                        owner.type(), owner.id(), UNFINISHED)
                .stream()
                .map(JobStore::toDownload)
                .toList();
    }

    /** Что у владельца сейчас в работе — ответ на кнопку «Статус». */
    @Transactional(readOnly = true)
    public OwnerLoad load(Owner owner) {
        return new OwnerLoad(
                repository.countByOwnerTypeAndOwnerIdAndState(owner.type(), owner.id(), JobState.QUEUED),
                repository.countByOwnerTypeAndOwnerIdAndState(owner.type(), owner.id(), JobState.RUNNING));
    }

    /** Задача скачивания: кому, что и с какого момента. */
    public record DownloadJob(Owner owner, String url, Instant startedAt) {}

    /** Задачи владельца: сколько ждёт очереди и сколько считается прямо сейчас. */
    public record OwnerLoad(long queued, long running) {
        public long total() {
            return queued + running;
        }
    }

    /** Сколько задач ждёт очереди — только для логов и метрик. */
    @Transactional(readOnly = true)
    public long queued() {
        return repository.countByState(JobState.QUEUED);
    }

    /**
     * Возвращает в очередь задачи, оставшиеся в работе от прошлого запуска.
     * Вызывается на старте {@link JobWorker}.
     */
    @Transactional
    public int recoverStuck() {
        int recovered = repository.requeueRunning(Instant.now());
        if (recovered > 0) {
            log.warn("Возвращено в очередь задач, застрявших после перезапуска: {}", recovered);
        }
        return recovered;
    }

    /* ───────── helpers ───────── */

    private JobEntity require(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new IllegalStateException("Задача исчезла из базы: " + id));
    }

    private static DownloadJob toDownload(JobEntity e) {
        return new DownloadJob(e.owner(), e.getUrl(), e.getCreatedAt());
    }

    private static ProcessingJob toJob(JobEntity e) {
        return new ProcessingJob(
                e.getId(),
                e.owner(),
                e.getFilePath() == null ? null : Path.of(e.getFilePath()),
                e.getUrl(),
                e.getStage(),
                e.getDownloadId(),
                e.getMediaKind());
    }
}

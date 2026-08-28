package Bot.transcription;

/**
 * Выдача готовой расшифровки пользователю во всех форматах.
 *
 * <p>Ответственность: отправить текст сразу после транскрипции и добавить под
 * ним кнопки — субтитры, Word и выжимку. Один проход Whisper уже кладёт {@code .srt},
 * {@code .vtt}, {@code .json} и {@code .tsv} рядом с текстом, так что субтитры
 * достаются без единой лишней секунды на видеокарте; отдать их — вопрос одной
 * кнопки. Связан с {@link JobStore} (по задаче находится сама расшифровка),
 * {@link WordExporter} и {@link MessageSender}. Ключевые методы:
 * {@code deliver} и {@code sendFormat}.</p>
 *
 * <p>Кнопки предлагаются только для форматов, которые реально лежат на диске:
 * расшифровки, сделанные до появления субтитров, соседних файлов не имеют, и
 * обещать их кнопкой значило бы врать. По той же причине выжимка появляется,
 * только когда языковая модель и правда отвечает.</p>
 */
import Bot.config.Profiles;
import Bot.insight.InsightService;
import Bot.owner.Owner;
import Bot.processing.JobStore;
import Bot.telegram.Keyboards;
import Bot.telegram.MessageSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Profile(Profiles.HOME)
@Service
@RequiredArgsConstructor
@Slf4j
public class TranscriptDeliveryService {

    /** Что предлагаем кнопками. Сам текст уже отправлен, поэтому TXT здесь нет. */
    private static final List<TranscriptFormat> OFFERED =
            List.of(TranscriptFormat.SRT, TranscriptFormat.VTT, TranscriptFormat.DOCX);

    private final JobStore jobStore;
    private final WordExporter wordExporter;
    private final MessageSender messageSender;
    private final InsightService insights;

    /**
     * Отправляет текст расшифровки и кнопки с остальными форматами.
     *
     * @param txt файл, который вернул {@link TranscribeExecutor}
     */
    public void deliver(UUID jobId, long chatId, Path txt) {
        List<TranscriptFormat> available = availableFormats(txt);
        boolean summary = insights.ready();

        if (available.isEmpty() && !summary) {
            // Ни субтитров, ни Word, ни модели — отправляем как раньше
            messageSender.sendTranscript(chatId, txt, null);
            return;
        }

        log.info("Расшифровка отправлена: chatId={}, jobId={}, доступно форматов={}, выжимка={}",
                chatId, jobId, available.size(), summary);
        messageSender.sendTranscript(chatId, txt,
                Keyboards.underTranscript(jobId.toString(), available, summary));
    }

    /**
     * Отдаёт расшифровку в запрошенном формате — обработчик нажатия кнопки.
     *
     * <p>Устаревшая кнопка (файл удалён ночной чисткой, реестр потерял запись)
     * не должна оставлять пользователя без ответа: на любой отказ уходит
     * объяснение и главное меню.</p>
     */
    public void sendFormat(long chatId, String id, TranscriptFormat format) {
        Optional<Path> found = transcript(chatId, id);
        if (found.isEmpty()) {
            messageSender.sendMessageWithKeyboard(chatId,
                    "🕓 Эта расшифровка больше недоступна — файлы хранятся ограниченное время. "
                            + "Пришлите запись ещё раз, если она нужна.",
                    null, Keyboards.mainMenu());
            return;
        }

        Path txt = found.get();
        try {
            Path file = format == TranscriptFormat.DOCX ? wordExporter.export(txt) : format.fileFor(txt);

            if (!Files.isRegularFile(file)) {
                log.info("Формат недоступен для расшифровки: id={}, формат={}, ожидался файл={}",
                        id, format, file.getFileName());
                messageSender.sendMessageWithKeyboard(chatId,
                        "🤷 Для этой расшифровки такого формата нет.",
                        null, Keyboards.mainMenu());
                return;
            }

            log.info("Отдаю расшифровку в формате {}: chatId={}, id={}", format, chatId, id);
            messageSender.sendFile(chatId, file, format.caption(),
                    () -> messageSender.sendMessage(chatId,
                            "❌ Не удалось отправить файл. Попробуйте ещё раз."));

        } catch (Exception e) {
            log.error("Не удалось подготовить формат {}: chatId={}, id={}", format, chatId, id, e);
            messageSender.sendMessageWithKeyboard(chatId,
                    "❌ Не получилось собрать файл в этом формате.",
                    null, Keyboards.mainMenu());
        }
    }

    /* ───────── helpers ───────── */

    /**
     * Находит расшифровку по идентификатору из кнопки.
     *
     * <p>Идентификатор — это id задачи. Нечитаемый id (кнопка из совсем старого
     * сообщения) и чужая задача обрабатываются одинаково: расшифровки нет.
     * Файл проверяется отдельно — его могла удалить ночная чистка.</p>
     */
    private Optional<Path> transcript(long chatId, String id) {
        UUID jobId;
        try {
            jobId = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            log.warn("Неразбираемый идентификатор расшифровки: chatId={}, id='{}'", chatId, id);
            return Optional.empty();
        }
        return jobStore.transcriptOf(jobId, Owner.telegram(chatId))
                .filter(Files::isRegularFile);
    }

    /**
     * Форматы, которые реально можно отдать: субтитры — если Whisper их положил,
     * Word — если есть из чего собирать, то есть всегда, когда есть текст.
     */
    private List<TranscriptFormat> availableFormats(Path txt) {
        List<TranscriptFormat> available = new ArrayList<>(OFFERED.size());
        for (TranscriptFormat format : OFFERED) {
            if (format == TranscriptFormat.DOCX
                    ? Files.isRegularFile(txt)
                    : Files.isRegularFile(format.fileFor(txt))) {
                available.add(format);
            }
        }
        return available;
    }
}

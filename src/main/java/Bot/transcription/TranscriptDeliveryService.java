package Bot.transcription;

/**
 * Выдача готовой расшифровки пользователю во всех форматах.
 *
 * <p>Ответственность: отправить текст сразу после транскрипции и добавить под
 * ним кнопки — субтитры и Word. Один проход Whisper уже кладёт {@code .srt},
 * {@code .vtt}, {@code .json} и {@code .tsv} рядом с текстом, так что субтитры
 * достаются без единой лишней секунды на видеокарте; отдать их — вопрос одной
 * кнопки. Связан с {@link TranscriptRegistry} (идентификаторы для кнопок),
 * {@link WordExporter} и {@link MessageSender}. Ключевые методы:
 * {@code deliver} и {@code sendFormat}.</p>
 *
 * <p>Кнопки предлагаются только для форматов, которые реально лежат на диске:
 * расшифровки, сделанные до появления субтитров, соседних файлов не имеют, и
 * обещать их кнопкой значило бы врать.</p>
 */
import Bot.telegram.Keyboards;
import Bot.telegram.MessageSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class TranscriptDeliveryService {

    /** Что предлагаем кнопками. Сам текст уже отправлен, поэтому TXT здесь нет. */
    private static final List<TranscriptFormat> OFFERED =
            List.of(TranscriptFormat.SRT, TranscriptFormat.VTT, TranscriptFormat.DOCX);

    private final TranscriptRegistry registry;
    private final WordExporter wordExporter;
    private final MessageSender messageSender;

    /**
     * Отправляет текст расшифровки и кнопки с остальными форматами.
     *
     * @param txt файл, который вернул {@link TranscribeExecutor}
     */
    public void deliver(long chatId, Path txt) {
        List<TranscriptFormat> available = availableFormats(txt);

        if (available.isEmpty()) {
            // Ни субтитров, ни возможности собрать Word — отправляем как раньше
            messageSender.sendTranscript(chatId, txt, null);
            return;
        }

        String id = registry.register(txt, chatId);
        log.info("Расшифровка отправлена: chatId={}, id={}, доступно форматов={}",
                chatId, id, available.size());
        messageSender.sendTranscript(chatId, txt, Keyboards.transcriptFormats(id, available));
    }

    /**
     * Отдаёт расшифровку в запрошенном формате — обработчик нажатия кнопки.
     *
     * <p>Устаревшая кнопка (файл удалён ночной чисткой, реестр потерял запись)
     * не должна оставлять пользователя без ответа: на любой отказ уходит
     * объяснение и главное меню.</p>
     */
    public void sendFormat(long chatId, String id, TranscriptFormat format) {
        Optional<TranscriptRegistry.Transcript> found = registry.resolve(id, chatId);
        if (found.isEmpty()) {
            messageSender.sendMessageWithKeyboard(chatId,
                    "🕓 Эта расшифровка больше недоступна — файлы хранятся ограниченное время. "
                            + "Пришлите запись ещё раз, если она нужна.",
                    null, Keyboards.mainMenu());
            return;
        }

        Path txt = found.get().txt();
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

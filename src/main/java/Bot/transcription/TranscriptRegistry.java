package Bot.transcription;

/**
 * Реестр готовых расшифровок: короткий идентификатор → путь к {@code .txt}.
 *
 * <p>Ответственность: выдать под каждую расшифровку идентификатор, который
 * помещается в {@code callback_data} кнопки, и вернуть по нему путь к файлу.
 * Нужен именно реестр, а не путь в самой кнопке: лимит Telegram — 64 байта, а
 * имена файлов здесь длинные и в кириллице.</p>
 *
 * <p>Записи переживают перезапуск через TSV ({@code transcript.store}). Своего
 * срока годности у записи нет — она живёт ровно столько, сколько лежит файл:
 * {@link Bot.service.FileCleanupWorker} удаляет старые расшифровки, и запись
 * отваливается вместе с ними. Отдельный TTL был бы вторым источником правды и
 * гасил бы кнопку, под которой файл ещё есть.</p>
 */
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Slf4j
public class TranscriptRegistry {

    /** Файл, в котором реестр переживает перезапуск. */
    @Value("${transcript.store}")
    private String storePath;

    /**
     * 9 байт → 12 символов Base64. Идентификатор не секрет (владелец проверяется
     * отдельно), но и подбирать соседние расшифровки перебором не должно быть
     * можно — 72 бита это исключают.
     */
    private static final int ID_BYTES = 9;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    /** id → расшифровка. ConcurrentHashMap — пишет воркер, читает поток бота. */
    private final Map<String, Transcript> transcripts = new ConcurrentHashMap<>();

    private Path store;

    /** Поднимает записи с диска, отбрасывая те, чей файл уже удалён. */
    @PostConstruct
    void load() {
        store = Path.of(storePath).toAbsolutePath().normalize();
        if (!Files.exists(store)) {
            log.info("Файл реестра расшифровок не найден, начинаем с пустого: {}", store);
            return;
        }

        int restored = 0;
        int dropped = 0;
        try {
            for (String line : Files.readAllLines(store, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                // id \t chatId \t absolutePath
                String[] parts = line.split("\t", 3);
                if (parts.length < 3) {
                    dropped++;
                    continue;
                }
                try {
                    Path txt = Path.of(parts[2]).toAbsolutePath().normalize();
                    if (!Files.isRegularFile(txt)) {
                        dropped++;
                        continue;
                    }
                    transcripts.put(parts[0], new Transcript(txt, Long.parseLong(parts[1])));
                    restored++;
                } catch (RuntimeException e) {
                    dropped++;
                }
            }
        } catch (IOException e) {
            log.warn("Не удалось прочитать реестр расшифровок: {}", store, e);
            return;
        }

        log.info("Восстановлено расшифровок: {}, отброшено: {}", restored, dropped);
        if (dropped > 0) {
            persistAll();
        }
    }

    /**
     * Регистрирует готовую расшифровку и возвращает идентификатор для кнопок.
     *
     * @param txt    путь к {@code .txt}; остальные форматы лежат рядом
     * @param chatId владелец расшифровки — по нему проверяется доступ
     */
    public String register(Path txt, long chatId) {
        String id = generateId();
        Transcript info = new Transcript(txt.toAbsolutePath().normalize(), chatId);
        transcripts.put(id, info);
        append(id, info);
        log.debug("Расшифровка зарегистрирована: id={}, chatId={}, file={}",
                id, chatId, info.txt().getFileName());
        return id;
    }

    /**
     * Возвращает расшифровку, если идентификатор известен, файл на месте и
     * запрашивает её тот же чат, которому она принадлежит.
     */
    public Optional<Transcript> resolve(String id, long chatId) {
        Transcript info = transcripts.get(id);
        if (info == null) {
            return Optional.empty();
        }
        if (info.chatId() != chatId) {
            log.warn("Запрос чужой расшифровки: id={}, запросил chatId={}, владелец chatId={}",
                    id, chatId, info.chatId());
            return Optional.empty();
        }
        if (!Files.isRegularFile(info.txt())) {
            log.info("Файл расшифровки уже удалён: id={}, file={}", id, info.txt());
            return Optional.empty();
        }
        return Optional.of(info);
    }

    /** Выметает записи, чьи файлы удалила ночная чистка, — иначе карта только растёт. */
    @Scheduled(fixedRate = 6 * 60 * 60 * 1000) // раз в 6 часов
    void purgeMissing() {
        int before = transcripts.size();
        transcripts.entrySet().removeIf(e -> !Files.isRegularFile(e.getValue().txt()));
        int removed = before - transcripts.size();
        if (removed > 0) {
            persistAll();
            log.debug("Из реестра убрано расшифровок без файлов: {}", removed);
        }
    }

    /* ───────── helpers ───────── */

    private String generateId() {
        byte[] buf = new byte[ID_BYTES];
        RANDOM.nextBytes(buf);
        // URL-safe Base64: только [A-Za-z0-9-_], поэтому разделитель ':' в
        // callback_data остаётся однозначным
        return URL_ENCODER.encodeToString(buf);
    }

    /** Обычный путь регистрации — дописать строку, не переписывая файл. */
    private synchronized void append(String id, Transcript info) {
        try {
            Path parent = store.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(store, line(id, info), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            // Кнопки работают до перезапуска — не срываем выдачу расшифровки
            log.warn("Не удалось сохранить запись реестра расшифровок: {}", store, e);
        }
    }

    /** Переписывает файл целиком — после удаления записей. */
    private synchronized void persistAll() {
        List<String> lines = new ArrayList<>(transcripts.size());
        transcripts.forEach((id, info) -> lines.add(line(id, info)));
        try {
            Path parent = store.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(store, String.join("", lines), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            log.warn("Не удалось переписать реестр расшифровок: {}", store, e);
        }
    }

    private String line(String id, Transcript info) {
        return id + '\t' + info.chatId() + '\t' + info.txt() + '\n';
    }

    /** Запись реестра: где лежит текст и чей он. */
    public record Transcript(Path txt, long chatId) {}
}

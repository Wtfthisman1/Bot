package Bot.download;

/**
 * Реестр ссылок на скачивание файлов.
 *
 * <p>Ответственность: выдать непредсказуемый токен, связать его с файлом и
 * владельцем, проверить при обращении срок годности и наличие файла, вычистить
 * просроченное. Используется {@link DownloadService} (регистрация) и
 * {@link DownloadController} (резолв). Основные методы: {@code register},
 * {@code resolve}, фоновая {@code purgeExpired}.</p>
 *
 * <p>Хранится всё в базе. До этого реестр жил в памяти с дублированием в
 * TSV-файл: файл появился потому, что иначе ссылка с обещанным сроком в сутки
 * умирала на первом же перезапуске бота. С базой второй механизм не нужен, а
 * главное — файл рядом с видео не увидит вторая машина, когда бот и воркер
 * разъедутся.</p>
 */
import Bot.config.Profiles;
import Bot.owner.Owner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

@Profile(Profiles.HOME)
@Component
@RequiredArgsConstructor
@Slf4j
public class DownloadTokenRegistry {

    /** Срок жизни ссылки в часах (по умолчанию 24). */
    @Value("${download.token.ttl-hours:24}")
    private int ttlHours;

    /** 24 байта ≈ 192 бита энтропии — подобрать перебором невозможно. */
    private static final int TOKEN_BYTES = 24;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final DownloadTokenRepository repository;

    /**
     * Регистрирует файл и возвращает непредсказуемый токен для ссылки.
     *
     * @param filePath путь к уже скачанному файлу
     * @param owner    кому выдана ссылка — для аудита и будущей страницы «мои файлы»
     * @return токен, который вставляется в URL вида {baseUrl}/download/{token}
     */
    @Transactional
    public String register(Path filePath, Owner owner) {
        String token = generateToken();
        Instant expiresAt = Instant.now().plus(Duration.ofHours(ttlHours));
        Path normalized = filePath.toAbsolutePath().normalize();

        DownloadTokenEntity entity = new DownloadTokenEntity();
        entity.setToken(token);
        entity.setOwnerType(owner.type());
        entity.setOwnerId(owner.id());
        entity.setFilePath(normalized.toString());
        entity.setExpiresAt(expiresAt);
        entity.setCreatedAt(Instant.now());
        repository.save(entity);

        log.info("Зарегистрирована download-ссылка: владелец={}, file={}, expiresAt={}",
                owner, normalized.getFileName(), expiresAt);
        return token;
    }

    /**
     * Возвращает путь к файлу по токену, если токен валиден, не просрочен
     * и файл по-прежнему существует. Иначе — пустой Optional.
     *
     * <p>Просроченная запись удаляется прямо здесь, не дожидаясь фоновой
     * чистки: раз уж мы её всё равно прочитали.</p>
     */
    @Transactional
    public Optional<Path> resolve(String token) {
        Optional<DownloadTokenEntity> found = repository.findById(token);
        if (found.isEmpty()) {
            return Optional.empty();
        }

        DownloadTokenEntity entity = found.get();
        if (entity.getExpiresAt().isBefore(Instant.now())) {
            repository.delete(entity);
            log.debug("Токен просрочен и удалён");
            return Optional.empty();
        }

        Path file = Path.of(entity.getFilePath());
        if (!Files.isRegularFile(file)) {
            // Файл мог унести ночная чистка: ссылка формально жива, отдавать нечего
            log.warn("Файл для токена отсутствует на диске: file={}", file);
            return Optional.empty();
        }

        return Optional.of(file);
    }

    /** Фоновая чистка просроченных ссылок. Требует {@code @EnableScheduling}. */
    @Scheduled(fixedRate = 30 * 60 * 1000) // каждые 30 минут
    @Transactional
    public void purgeExpired() {
        int removed = repository.deleteExpired(Instant.now());
        if (removed > 0) {
            log.debug("Очищено просроченных download-ссылок: {}", removed);
        }
    }

    /* ───────── helpers ───────── */

    private String generateToken() {
        byte[] buf = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(buf);
        // URL-safe Base64 без паддинга: только [A-Za-z0-9-_], без '/' и '.'
        return URL_ENCODER.encodeToString(buf);
    }
}

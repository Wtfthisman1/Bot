package Bot.handler;

/**
 * Состояние диалога с пользователем между сообщениями.
 *
 * <p>Сценарий двухшаговый и работает в обе стороны:</p>
 * <ul>
 *   <li>нажали кнопку → бот ждёт ссылку ({@link #awaitLink});</li>
 *   <li>прислали ссылку без кнопки → бот держит её и ждёт выбора действия
 *       ({@link #rememberUrl}).</li>
 *   <li>нажали «Разбор по теме» под расшифровкой → бот ждёт саму тему
 *       ({@link #awaitTopic}).</li>
 * </ul>
 *
 * <p>Для скачивания к действию добавляется вид медиа: аудио или видео. Выбор
 * спрашивается сразу после кнопки «Скачать», поэтому к моменту прихода ссылки
 * известно и действие, и формат — подтверждение уходит одним сообщением.</p>
 *
 * <p>{@link ConcurrentHashMap}: состояние читают и обработчик апдейтов, и
 * фоновые воркеры.</p>
 */
import Bot.processing.MediaKind;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class UserSessionService {

    /** Что бот сделает со ссылкой. */
    public enum Mode {
        TRANSCRIBE,
        DOWNLOAD
    }

    /** Выбранное действие вместе с видом медиа. */
    public record Pending(Mode mode, MediaKind media) {
    }

    /**
     * Ровно одно из полей заполнено: ждём ссылку под выбранное действие, держим
     * ссылку до выбора действия или ждём тему для разбора уже готовой
     * расшифровки.
     */
    private record Session(Pending awaiting, String pendingUrl, String topicJobId) {
    }

    private final Map<Long, Session> sessions = new ConcurrentHashMap<>();

    /** Действие выбрано — ждём ссылку. */
    public void awaitLink(long chatId, Mode mode, MediaKind media) {
        sessions.put(chatId, new Session(new Pending(mode, media), null, null));
        log.debug("Ожидание ссылки: chatId={}, mode={}, media={}", chatId, mode, media);
    }

    /** Ссылка пришла первой — держим её до выбора действия. */
    public void rememberUrl(long chatId, String url) {
        sessions.put(chatId, new Session(null, url, null));
        log.debug("Ссылка сохранена до выбора действия: chatId={}", chatId);
    }

    /** Нажат «Разбор по теме» — ждём, что именно искать в этой расшифровке. */
    public void awaitTopic(long chatId, String jobId) {
        sessions.put(chatId, new Session(null, null, jobId));
        log.debug("Ожидание темы разбора: chatId={}, jobId={}", chatId, jobId);
    }

    /**
     * Забирает выбранное действие и завершает шаг ожидания.
     * Возвращает пустое значение, если бот ссылку не ждал.
     */
    public Optional<Pending> takeAwaiting(long chatId) {
        Session session = sessions.get(chatId);
        if (session == null || session.awaiting() == null) {
            return Optional.empty();
        }
        sessions.remove(chatId);
        return Optional.of(session.awaiting());
    }

    /**
     * Забирает отложенную ссылку и завершает шаг ожидания.
     *
     * <p>Кнопка «Скачать» состояние не трогает — она лишь спрашивает формат,
     * поэтому ссылка доживает до второго нажатия, которое её и забирает.</p>
     */
    public Optional<String> takePendingUrl(long chatId) {
        Session session = sessions.get(chatId);
        if (session == null || session.pendingUrl() == null) {
            return Optional.empty();
        }
        sessions.remove(chatId);
        return Optional.of(session.pendingUrl());
    }

    /**
     * Забирает расшифровку, для которой ждали тему, и завершает шаг ожидания.
     *
     * <p>Пусто — бот темы не ждал, и присланный текст надо разбирать как
     * обычно: ссылка это или просьба показать меню.</p>
     */
    public Optional<String> takeTopicJob(long chatId) {
        Session session = sessions.get(chatId);
        if (session == null || session.topicJobId() == null) {
            return Optional.empty();
        }
        sessions.remove(chatId);
        return Optional.of(session.topicJobId());
    }

    /** Ждёт ли бот сейчас ссылку от пользователя. */
    public boolean isAwaitingLink(long chatId) {
        Session session = sessions.get(chatId);
        return session != null && session.awaiting() != null;
    }

    /** Сбрасывает состояние — по «Отмена», /start и после ошибок. */
    public void clear(long chatId) {
        if (sessions.remove(chatId) != null) {
            log.debug("Состояние диалога сброшено: chatId={}", chatId);
        }
    }
}

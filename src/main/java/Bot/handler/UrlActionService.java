package Bot.handler;

/**
 * Единая точка запуска работы по ссылке.
 *
 * <p>Ответственность: провалидировать платформу, поставить задачу в очередь
 * (транскрипция) или создать задачу загрузки, и ответить пользователю одним
 * коротким подтверждением. Сюда сходятся оба пути — «нажал кнопку, потом
 * прислал ссылку» и «прислал ссылку, потом нажал кнопку», поэтому текст ответа
 * и правила валидации не расходятся между ними.</p>
 *
 * <p>Раньше эту роль делил {@code ActionChoiceService}, который доставал
 * {@code MessageHandler} через {@code ApplicationContext.getBean} — обход
 * циклической зависимости. Зависимость разорвана по-настоящему: сервис знает
 * только про {@link HomeApi} — и не знает, где эта очередь физически стоит.</p>
 */
import Bot.handler.UserSessionService.Mode;
import Bot.handler.UserSessionService.Pending;
import Bot.home.HomeApi;
import Bot.owner.Owner;
import Bot.processing.MediaKind;
import Bot.service.SupportedPlatforms;
import Bot.telegram.Keyboards;
import Bot.telegram.MessageSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class UrlActionService {

    /** Больше пяти ссылок за раз очередь принимает, но пользователю столько не нужно. */
    public static final int MAX_URLS_PER_MESSAGE = 5;

    private final HomeApi home;
    private final MessageSender messageSender;
    private final SupportedPlatforms supportedPlatforms;

    /**
     * Запускает выбранное действие по одной ссылке.
     *
     * @return {@code true}, если задача принята в работу
     */
    public boolean start(long chatId, Pending pending, String url, String userName) {
        return start(chatId, pending, List.of(url), userName);
    }

    /**
     * Запускает выбранное действие по списку ссылок и отправляет одно
     * подтверждение на всю пачку.
     *
     * @return {@code true}, если хотя бы одна задача принята в работу
     */
    public boolean start(long chatId, Pending pending, List<String> urls, String userName) {
        Mode mode = pending.mode();
        MediaKind media = pending.media();

        List<String> accepted = urls.stream()
                .limit(MAX_URLS_PER_MESSAGE)
                .filter(supportedPlatforms::isSupported)
                .toList();

        if (accepted.isEmpty()) {
            log.info("Ссылки отклонены как неподдерживаемые: chatId={}, mode={}, прислано={}",
                    chatId, mode, urls.size());
            messageSender.sendMessageWithKeyboard(chatId,
                    "❌ Не могу работать с этой ссылкой.\n\n" + supportedPlatforms.supportedListText(),
                    null, Keyboards.mainMenu());
            return false;
        }

        Owner owner = Owner.telegram(chatId);
        for (String url : accepted) {
            switch (mode) {
                case TRANSCRIBE -> home.transcribeLink(owner, url);
                case DOWNLOAD -> home.downloadLink(owner, url, media);
            }
        }

        log.info("Запущено действие {} ({}): chatId={}, кто={}, ссылок={}",
                mode, media, chatId, userName, accepted.size());
        messageSender.sendMessage(chatId, "✅ Всё запущено, ожидайте.");
        return true;
    }
}

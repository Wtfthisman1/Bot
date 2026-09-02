package Bot.site;

/**
 * Сообщение в чат: «в ваш аккаунт только что вошли».
 *
 * <p>Ответственность: закрыть единственный оставшийся способ увести аккаунт
 * через бота — пересланную своими руками ссылку. Отнять у человека возможность
 * переслать что угодно нельзя, но можно сделать так, чтобы он узнал об этом
 * сразу, а не когда пропадут расшифровки.</p>
 *
 * <p>Отправка не должна ломать вход: человек уже вошёл, и молчащий Telegram —
 * не повод показывать ему ошибку. Поэтому все сбои остаются в журнале.</p>
 */
import Bot.config.Profiles;
import Bot.telegram.MessageSender;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

@Profile(Profiles.HOME)
@Component
@RequiredArgsConstructor
@Slf4j
public class LoginNotice {

    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    /** Строка браузера бывает длиннее сообщения — режем её до узнаваемого. */
    private static final int AGENT_LIMIT = 80;

    private final MessageSender messageSender;

    /** Говорит в чат, что по выданной им ссылке вошли. */
    public void entered(long chatId, HttpServletRequest request) {
        try {
            String text = """
                    🔐 <b>Вход на сайт выполнен</b>

                    🕒 %s
                    🌐 %s
                    💻 %s

                    Если это были не вы — откройте кабинет и нажмите
                    «Выйти на всех устройствах». Ссылку входа больше никому
                    не пересылайте.
                    """.formatted(
                    WHEN.format(ZonedDateTime.now(ZoneId.systemDefault())),
                    MessageSender.escapeHtml(ClientAddress.of(request)),
                    MessageSender.escapeHtml(agent(request)));
            messageSender.sendMessage(chatId, text.strip(), "HTML");
        } catch (Exception e) {
            // Человек уже вошёл: сорвавшееся уведомление — повод для журнала,
            // а не для отказа во входе
            log.warn("Не удалось сообщить о входе в чат: chatId={}", chatId, e);
        }
    }

    /**
     * Говорит в чат, что пароль от аккаунта только что сменили.
     *
     * <p>Смена пароля — первый ход того, кто увёл аккаунт: она закрывает
     * настоящему владельцу вход раньше, чем он заметит пропажу. Поэтому о ней
     * узнаёт чат, а не только тот, кто её сделал.</p>
     *
     * @param owners все владельцы аккаунта; сообщение уходит в телеграмные
     */
    public void passwordChanged(java.util.List<Bot.owner.Owner> owners,
                                HttpServletRequest request) {
        String text = """
                🔑 <b>Пароль от аккаунта изменён</b>

                🕒 %s
                🌐 %s
                💻 %s

                Если это были не вы — войдите по кнопке «Войти на сайт»
                и смените пароль снова: остальные сеансы при этом закроются.
                """.formatted(
                WHEN.format(ZonedDateTime.now(ZoneId.systemDefault())),
                MessageSender.escapeHtml(ClientAddress.of(request)),
                MessageSender.escapeHtml(agent(request)));

        for (Bot.owner.Owner owner : owners) {
            if (!owner.isTelegram()) {
                continue;
            }
            try {
                messageSender.sendMessage(owner.telegramChatId(), text.strip(), "HTML");
            } catch (Exception e) {
                // Пароль уже сменён: молчащий Telegram — повод для журнала,
                // а не для отката
                log.warn("Не удалось сообщить о смене пароля: владелец={}", owner, e);
            }
        }
    }

    private static String agent(HttpServletRequest request) {
        String value = request.getHeader("User-Agent");
        if (value == null || value.isBlank()) {
            return "браузер не представился";
        }
        String clean = value.strip();
        return clean.length() <= AGENT_LIMIT ? clean : clean.substring(0, AGENT_LIMIT) + "…";
    }
}

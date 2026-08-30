package Bot.site;

/**
 * Двери сайта: главная, вход и регистрация.
 *
 * <p>Ответственность: принять то, чем человек себя предъявляет, и завести
 * сессию. Способов три — почта с паролем, Telegram и Google, — и все три
 * заканчиваются одинаково: {@link SessionLogin} кладёт аккаунт в сессию, а
 * дальше страницы не знают, какой дверью вошли.</p>
 *
 * <p>Ошибки входа намеренно безликие: «почта или пароль не подходят». По
 * разнице ответов подбирают список существующих адресов.</p>
 */
import Bot.account.AccountService;
import Bot.account.BotLoginService;
import Bot.account.IdentityProvider;
import Bot.config.Profiles;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;
import java.util.Optional;

@Profile(Profiles.HOME)
@Controller
@RequiredArgsConstructor
@Slf4j
public class AuthController {

    /** Ключ в сессии, под которым лежит начатый вход через бота. */
    private static final String BOT_LOGIN_CODE = "botLoginCode";

    /** Число сверки — рядом с кодом и в той же сессии: его показывает страница. */
    private static final String BOT_LOGIN_NUMBER = "botLoginNumber";

    /** Имя бота без «@» — виджет входа Telegram узнаёт бота по нему. */
    @Value("${bot.name:}")
    private String botName;

    private final AccountService accounts;
    private final LoginAttempts attempts;
    private final BotLoginService botLogins;
    private final SessionLogin sessionLogin;
    private final TelegramLoginVerifier telegramLogin;
    private final ObjectProvider<ClientRegistrationRepository> googleClients;

    @GetMapping("/")
    public String index(@AuthenticationPrincipal AccountPrincipal principal, Model model) {
        if (principal != null) {
            return "redirect:/cabinet";
        }
        fillLoginOptions(model);
        return "index";
    }

    @GetMapping("/login")
    public String loginPage(@AuthenticationPrincipal AccountPrincipal principal,
                            @RequestParam(required = false) String error, Model model) {
        if (principal != null) {
            return "redirect:/cabinet";
        }
        fillLoginOptions(model);
        model.addAttribute("error", error);
        return "login";
    }

    @PostMapping("/login")
    public String login(@RequestParam String email, @RequestParam String password,
                        HttpServletRequest request, HttpServletResponse response, Model model) {
        // Перебор пароля упирался только в скорость bcrypt: ни в приложении,
        // ни в nginx ограничения не было
        if (!attempts.allows(request)) {
            fillLoginOptions(model);
            model.addAttribute("error", attempts.refusal());
            return "login";
        }

        Optional<AccountService.Account> account = accounts.authenticate(email, password);
        if (account.isEmpty()) {
            attempts.failed(request);
            fillLoginOptions(model);
            model.addAttribute("error", "Почта или пароль не подходят.");
            return "login";
        }

        attempts.succeeded(request);
        sessionLogin.signIn(request, response, account.get());
        return "redirect:/cabinet";
    }

    @GetMapping("/register")
    public String registerPage(@AuthenticationPrincipal AccountPrincipal principal, Model model) {
        if (principal != null) {
            return "redirect:/cabinet";
        }
        fillLoginOptions(model);
        return "register";
    }

    @PostMapping("/register")
    public String register(@RequestParam String email,
                           @RequestParam String password,
                           @RequestParam(required = false) String displayName,
                           HttpServletRequest request, HttpServletResponse response, Model model) {
        // Регистрация без ограничения — это бесплатный конвейер аккаунтов,
        // а с ними и бесплатных квот
        if (!attempts.allows(request)) {
            return registrationFailed(model, attempts.refusal());
        }

        try {
            AccountService.Account account = accounts.register(email, password, displayName);
            sessionLogin.signIn(request, response, account);
            return "redirect:/cabinet";
        } catch (AccountService.EmailTakenException e) {
            attempts.failed(request);
            return registrationFailed(model, "На эту почту аккаунт уже заведён. Попробуйте войти.");
        } catch (IllegalArgumentException e) {
            attempts.failed(request);
            return registrationFailed(model, e.getMessage());
        }
    }

    /**
     * Возврат из виджета Telegram.
     *
     * <p>Аккаунт ищется по тому же идентификатору, которым бот подписывает
     * задачи из переписки, поэтому в кабинете сразу видно всё, что человек уже
     * присылал в чат, — привязывать ничего не нужно.</p>
     */
    @GetMapping("/auth/telegram")
    public String telegram(@RequestParam Map<String, String> params,
                           HttpServletRequest request, HttpServletResponse response, Model model) {
        Optional<TelegramLoginVerifier.TelegramUser> user = telegramLogin.verify(params);
        if (user.isEmpty()) {
            fillLoginOptions(model);
            model.addAttribute("error", "Вход через Telegram не подтвердился. Попробуйте ещё раз.");
            return "login";
        }

        AccountService.Account account = accounts.findOrCreateByIdentity(
                IdentityProvider.TELEGRAM, user.get().id(), user.get().name(), null);
        sessionLogin.signIn(request, response, account);
        return "redirect:/cabinet";
    }

    /* ───────── вход через бота ───────── */

    /**
     * Начинает вход через бота: выдаёт код и уводит на страницу ожидания.
     *
     * <p>Код кладётся в сессию, а не в адрес: войти должен именно тот браузер,
     * который вход начал. Иначе подсмотренная ссылка на страницу ожидания
     * пускала бы в чужой аккаунт вместе с подтверждением.</p>
     */
    @PostMapping("/auth/bot")
    public String startBotLogin(HttpServletRequest request) {
        BotLoginService.Issued issued = botLogins.issue();
        request.getSession(true).setAttribute(BOT_LOGIN_CODE, issued.code());
        request.getSession(true).setAttribute(BOT_LOGIN_NUMBER, issued.checkNumber());
        return "redirect:/auth/bot/wait";
    }

    /**
     * Страница ожидания: обновляется сама, пока не придёт подтверждение.
     *
     * <p>Обновление сделано {@code meta refresh}, а не скриптом: страница
     * должна работать и там, где JavaScript выключен, а опрос раз в три
     * секунды на пять минут — это меньше сотни запросов.</p>
     */
    @GetMapping("/auth/bot/wait")
    public String waitForBotLogin(HttpServletRequest request, HttpServletResponse response,
                                  Model model) {
        String code = (String) request.getSession(true).getAttribute(BOT_LOGIN_CODE);
        if (code == null) {
            return "redirect:/login";
        }

        BotLoginService.State state = botLogins.stateOf(code);
        if (state == BotLoginService.State.CONFIRMED) {
            Optional<AccountService.Account> account = botLogins.claim(code);
            request.getSession(true).removeAttribute(BOT_LOGIN_CODE);
            request.getSession(true).removeAttribute(BOT_LOGIN_NUMBER);
            if (account.isPresent()) {
                sessionLogin.signIn(request, response, account.get());
                return "redirect:/cabinet";
            }
        }

        if (state == BotLoginService.State.EXPIRED || state == BotLoginService.State.UNKNOWN) {
            request.getSession(true).removeAttribute(BOT_LOGIN_CODE);
            request.getSession(true).removeAttribute(BOT_LOGIN_NUMBER);
            fillLoginOptions(model);
            model.addAttribute("error", "Время ожидания вышло. Начните вход заново.");
            return "login";
        }

        model.addAttribute("botName", botName);
        model.addAttribute("code", code);
        model.addAttribute("checkNumber", request.getSession(true).getAttribute(BOT_LOGIN_NUMBER));
        model.addAttribute("botLink", "https://t.me/" + botName + "?start=login_" + code);
        return "bot-login";
    }

    /* ───────── helpers ───────── */

    private String registrationFailed(Model model, String message) {
        fillLoginOptions(model);
        model.addAttribute("error", message);
        return "register";
    }

    /** Какие двери показывать: Google появляется только с настроенными ключами. */
    private void fillLoginOptions(Model model) {
        model.addAttribute("botName", botName);
        model.addAttribute("googleEnabled", googleClients.getIfAvailable() != null);
    }
}

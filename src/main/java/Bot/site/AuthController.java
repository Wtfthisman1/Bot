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
 *
 * <p>Вход через бота сюда только приходит: ссылку выдаёт сам бот тому, кто её
 * попросил, а здесь она гасится. Начинать вход со страницы больше нельзя, и
 * это не упрощение — раньше код заводил браузер, а подтверждать шли в чат, и
 * ссылку с чужим кодом можно было прислать постороннему.</p>
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;
import java.util.Optional;

@Profile(Profiles.HOME)
@Controller
@RequiredArgsConstructor
@Slf4j
public class AuthController {

    /** Имя бота без «@» — виджет входа Telegram узнаёт бота по нему. */
    @Value("${bot.name:}")
    private String botName;

    private final AccountService accounts;
    private final LoginAttempts attempts;
    private final BotLoginService botLogins;
    private final SessionLogin sessionLogin;
    private final TelegramLoginVerifier telegramLogin;
    private final LoginNotice loginNotice;
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
        sessionLogin.signIn(request, response, account.get(), SessionLogin.Door.PASSWORD);
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
        // Отдельный, более строгий счёт. Ответ «на эту почту аккаунт уже
        // заведён» позволяет перебором узнать, кто здесь зарегистрирован;
        // убрать сам ответ нечем, пока писем слать нечем, — поэтому перебору
        // ограничена скорость
        if (!attempts.allowsRegistration(request)) {
            return registrationFailed(model, attempts.registrationRefusal());
        }
        // Тратится до попытки и при любом исходе: перебор адресов состоит из
        // неудач, конвейер аккаунтов — из удач, считать надо и то, и другое
        attempts.spendRegistration(request);

        try {
            AccountService.Account account = accounts.register(email, password, displayName);
            // Удачная регистрация тоже тратит попытку: без этого счётчик ловил
            // только опечатки, а конвейер аккаунтов с одного адреса проходил
            // мимо него целиком
            attempts.spend(request);
            sessionLogin.signIn(request, response, account, SessionLogin.Door.PASSWORD);
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
        sessionLogin.signIn(request, response, account, SessionLogin.Door.TELEGRAM);
        return "redirect:/cabinet";
    }

    /* ───────── вход через бота ───────── */

    /**
     * Страница по ссылке из чата: «войти как …?».
     *
     * <p>Открытие ссылки её не гасит. По адресам ходят не только люди:
     * Telegram тянет предпросмотр, антивирусы и почтовые фильтры открывают их
     * сами, — и гашение на GET сожгло бы вход до того, как человек его увидит.
     * Поэтому вход завершает отдельное нажатие, то есть POST с токеном формы.</p>
     *
     * <p>Имя показывается, чтобы человек видел, в какой аккаунт его пускают:
     * ссылка могла прийти и не из его чата.</p>
     */
    @GetMapping("/auth/enter/{token}")
    public String enterPage(@PathVariable String token, Model model) {
        Optional<String> name = botLogins.nameOf(token);
        if (name.isEmpty()) {
            return expiredLink(model);
        }

        model.addAttribute("token", token);
        model.addAttribute("name", name.get());
        return "bot-login";
    }

    /**
     * Нажатие на странице: гасим ссылку и заводим сессию.
     *
     * <p>О входе тут же сообщается в чат — это единственное, что работает
     * против пересланной своими руками ссылки: человек видит вход сразу, а не
     * когда пропадут расшифровки.</p>
     */
    @PostMapping("/auth/enter")
    public String enter(@RequestParam String token, HttpServletRequest request,
                        HttpServletResponse response, Model model) {
        Optional<BotLoginService.Entry> entry = botLogins.claim(token);
        if (entry.isEmpty()) {
            return expiredLink(model);
        }

        sessionLogin.signIn(request, response, entry.get().account(), SessionLogin.Door.BOT_LINK);
        loginNotice.entered(entry.get().chatId(), request);
        return "redirect:/cabinet";
    }

    /* ───────── helpers ───────── */

    /**
     * Ссылка не годится — и почему именно, человеку знать незачем.
     *
     * <p>Неизвестная, просроченная и уже сработавшая отвечают одинаково: по
     * разнице ответов ссылки перебирали бы, а взять новую всё равно можно
     * только там, где выдали первую, — в чате.</p>
     */
    private String expiredLink(Model model) {
        fillLoginOptions(model);
        model.addAttribute("error",
                "Ссылка устарела или уже сработала. Попросите в боте новую — "
                        + "кнопка «Войти на сайт».");
        return "login";
    }

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

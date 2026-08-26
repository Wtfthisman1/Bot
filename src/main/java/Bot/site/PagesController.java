package Bot.site;

/**
 * Страницы, которые сайт обязан иметь: помощь, политика, условия.
 *
 * <p>Ответственность: отдать статичный текст и, если человек вошёл, показать
 * ему привычную шапку с именем и выходом. Больше здесь ничего нет — тексты
 * живут в шаблонах, а не в коде.</p>
 *
 * <p>Отдельный контроллер, а не пара методов в {@link AuthController}: там
 * двери и сессии, здесь — документы, и смешивать их значит каждый раз читать
 * лишнее.</p>
 */
import Bot.config.Profiles;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Profile(Profiles.HOME)
@Controller
public class PagesController {

    @GetMapping("/help")
    public String help(@AuthenticationPrincipal AccountPrincipal principal, Model model) {
        return page("help", principal, model);
    }

    @GetMapping("/privacy")
    public String privacy(@AuthenticationPrincipal AccountPrincipal principal, Model model) {
        return page("privacy", principal, model);
    }

    @GetMapping("/terms")
    public String terms(@AuthenticationPrincipal AccountPrincipal principal, Model model) {
        return page("terms", principal, model);
    }

    /** Шапка одна на весь сайт, и ей нужен аккаунт — даже когда его нет. */
    private String page(String view, AccountPrincipal principal, Model model) {
        model.addAttribute("account", principal);
        return view;
    }
}

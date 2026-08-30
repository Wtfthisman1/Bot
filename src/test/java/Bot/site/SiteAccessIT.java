package Bot.site;

/**
 * Правила доступа к сайту целиком: кабинет закрыт, вход по паролю работает, а
 * адреса, которыми бот пользовался до сайта, остались открытыми.
 *
 * <p>Проверяется именно то, что легко сломать одной строчкой в
 * {@link SiteSecurityConfig}: закрыть сессией {@code /download} — значит
 * оборвать все ссылки, уже разосланные в чаты.</p>
 */
import Bot.account.AccountRepository;
import Bot.account.AccountService;
import Bot.account.BotLoginService;
import Bot.owner.Owner;
import Bot.processing.JobStore;
import Bot.processing.ProcessingJob;
import Bot.support.PostgresTestContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("home")
@Import(PostgresTestContainer.class)
@TestPropertySource(properties = {
        "bot.key=111:TEST_TOKEN_NOT_REAL",
        "admin.chat.id=",
        "cleanup.enabled=false",
        "management.server.port=-1",
        "home.api.key=test-key"
})
class SiteAccessIT {

    private static final String EMAIL = "anya@example.com";
    private static final String PASSWORD = "очень-длинный-пароль";

    @Autowired MockMvc mvc;
    @Autowired AccountService accounts;
    @Autowired BotLoginService botLogins;
    @Autowired AccountRepository accountRepository;
    @Autowired JobStore jobs;

    @AfterEach
    void clean() {
        accountRepository.deleteAll();
    }

    @org.junit.jupiter.api.io.TempDir Path tmp;

    @Test
    void cabinetIsClosedToStrangers() throws Exception {
        mvc.perform(get("/cabinet"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void passwordLoginOpensTheCabinet() throws Exception {
        accounts.register(EMAIL, PASSWORD, "Аня");

        MvcResult login = mvc.perform(post("/login").with(csrf())
                        .param("email", EMAIL)
                        .param("password", PASSWORD))
                .andExpect(redirectedUrl("/cabinet"))
                .andReturn();

        mvc.perform(get("/cabinet").session(session(login)))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Мои задачи")));
    }

    /**
     * В строке готовой расшифровки есть путь и к чтению, и к обработке моделью.
     *
     * <p>Заодно единственная проверка самого шаблона строки: выражения
     * Thymeleaf компилятор не видит, и опечатка в ссылке вылезала бы только у
     * человека с непустой историей — то есть у всех, кроме тестов.</p>
     */
    @Test
    void finishedJobOffersReadingAndInsights() throws Exception {
        AccountService.Account account = accounts.register(EMAIL, PASSWORD, "Аня");
        MvcResult login = mvc.perform(post("/login").with(csrf())
                .param("email", EMAIL)
                .param("password", PASSWORD)).andReturn();

        Path txt = Files.writeString(tmp.resolve("лекция.txt"), "расшифровка");
        ProcessingJob job = jobs.enqueue(ProcessingJob.newFile(
                Owner.account(account.id().toString()), tmp.resolve("лекция.m4a")));
        jobs.claim();
        jobs.complete(job.id(), txt);

        mvc.perform(get("/cabinet").session(session(login)))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "/cabinet/transcript/" + job.id() + "#insights")));
    }

    @Test
    void wrongPasswordDoesNotOpenAnything() throws Exception {
        accounts.register(EMAIL, PASSWORD, "Аня");

        mvc.perform(post("/login").with(csrf())
                        .param("email", EMAIL)
                        .param("password", "не-тот-пароль"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("не подходят")));
    }

    /** Ссылки из чатов ведут сюда — закрыть этот адрес значит оборвать их все. */
    @Test
    void downloadLinksStayOpen() throws Exception {
        mvc.perform(get("/download/bogus")).andExpect(status().isNotFound());
    }

    /**
     * Форма открыта снаружи: вход её не сторожит.
     *
     * <p>410, а не 200, потому что токен выдуманный: сработавшая или
     * просроченная ссылка показывает объяснение вместо формы. Важно здесь то,
     * что ответ пришёл от самой формы, а не от входа на сайт.</p>
     */
    @Test
    void uploadFormStaysOpen() throws Exception {
        mvc.perform(get("/upload/BOGUS1")).andExpect(status().isGone());
    }

    /** Чужая задача выглядит ровно как несуществующая. */
    @Test
    void someoneElsesJobIsNotFound() throws Exception {
        accounts.register(EMAIL, PASSWORD, "Аня");
        MvcResult login = mvc.perform(post("/login").with(csrf())
                .param("email", EMAIL)
                .param("password", PASSWORD)).andReturn();

        ProcessingJob stranger = jobs.enqueue(ProcessingJob.newFile(
                Owner.account(UUID.randomUUID().toString()), Path.of("/tmp/чужое.mp3")));

        mvc.perform(get("/cabinet/files/" + stranger.id() + "/txt").session(session(login)))
                .andExpect(status().isNotFound());
    }

    /* ───────── helpers ───────── */

    private static org.springframework.mock.web.MockHttpSession session(MvcResult result) {
        return (org.springframework.mock.web.MockHttpSession) result.getRequest().getSession(false);
    }

    /**
     * Вход через бота целиком: страница ждёт, чат подтверждает, браузер входит.
     *
     * <p>Подтверждение здесь вызывается напрямую — в бою его делает нажатие
     * кнопки в чате, которое приходит на другую половину приложения.</p>
     */
    @Test
    void botLoginSignsInAfterConfirmation() throws Exception {
        MvcResult started = mvc.perform(post("/auth/bot").with(csrf()))
                .andExpect(redirectedUrl("/auth/bot/wait"))
                .andReturn();
        var session = session(started);

        // Пока подтверждения нет — страница ожидания со ссылкой на бота
        mvc.perform(get("/auth/bot/wait").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("t.me/")));

        String code = (String) session.getAttribute("botLoginCode");
        // Число берём из той же сессии: именно его показывает страница, и
        // именно совпадение с ним подтверждает вход
        int number = (Integer) session.getAttribute("botLoginNumber");
        assertThat(botLogins.confirm(4242L, code, "Аня", number))
                .isEqualTo(Bot.account.BotLoginService.Confirmation.CONFIRMED);

        mvc.perform(get("/auth/bot/wait").session(session))
                .andExpect(redirectedUrl("/cabinet"));
        // Вошедшему через Telegram привязывать нечего — блок показывает это
        mvc.perform(get("/cabinet").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Открыть бота")))
                .andExpect(content().string(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Подключить Telegram"))));
    }

    /** Без начатого входа страница ожидания смысла не имеет. */
    @Test
    void botLoginWaitWithoutCodeGoesBackToLogin() throws Exception {
        mvc.perform(get("/auth/bot/wait"))
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void indexIsOpen() throws Exception {
        assertThat(mvc.perform(get("/")).andExpect(status().isOk())).isNotNull();
    }

    /**
     * Формы обязаны нести csrf-токен.
     *
     * <p>Thymeleaf подставляет его только в форму с {@code th:action}: с
     * обычным {@code action} страница выглядит точно так же, но любой POST с
     * неё возвращает 403 — и это видно только в живом браузере.</p>
     */
    @Test
    void formsCarryCsrfToken() throws Exception {
        mvc.perform(get("/login"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"_csrf\"")));
        mvc.perform(get("/register"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"_csrf\"")));

        accounts.register(EMAIL, PASSWORD, "Аня");
        MvcResult login = mvc.perform(post("/login").with(csrf())
                .param("email", EMAIL)
                .param("password", PASSWORD)).andReturn();

        mvc.perform(get("/cabinet").session(session(login)))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"_csrf\"")));
    }

    @Test
    void registrationPageIsOpen() throws Exception {
        mvc.perform(get("/register"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Завести аккаунт")));
    }

    /**
     * Помощь, политика и условия открыты всем и рисуются без входа.
     *
     * <p>Шапка на них общая, а ей нужен аккаунт, которого у гостя нет: ошибка
     * в шаблоне здесь превратилась бы в 500 на страницах, которые сайт обязан
     * показывать.</p>
     */
    @Test
    void publicPagesAreOpen() throws Exception {
        for (String path : new String[]{"/help", "/privacy", "/terms"}) {
            mvc.perform(get(path)).andExpect(status().isOk());
        }
    }

    /** Строка истории рисуется своей веткой шаблона — её тоже надо пройти. */
    @Test
    void cabinetShowsOwnJobs() throws Exception {
        AccountService.Account account = accounts.register(EMAIL, PASSWORD, "Аня");
        MvcResult login = mvc.perform(post("/login").with(csrf())
                .param("email", EMAIL)
                .param("password", PASSWORD)).andReturn();

        jobs.enqueue(ProcessingJob.newLink(account.asOwner(), "https://vimeo.com/12345"));

        mvc.perform(get("/cabinet").session(session(login)))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("vimeo.com/12345")))
                // Состояние здесь не проверяется: воркер в том же контексте
                // успевает забрать задачу, и «В очереди» превращается в
                // «Скачивается» — гонка, к отрисовке строки отношения не имеющая
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Расшифровка")))
                // Аккаунт заведён почтой: боту он ещё не знаком, и это предлагается исправить
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Подключить Telegram")));
    }
}

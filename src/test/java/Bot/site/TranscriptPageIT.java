package Bot.site;

/**
 * Страница расшифровки: чтение, правка, перемотка и поиск.
 *
 * <p>Проверяется то, что ломается молча: чужая задача должна выглядеть как
 * несуществующая, запись обязана отдаваться кусками (без 206 браузер не
 * перематывает), а скачанный файл — собираться уже с правками.</p>
 */
import Bot.account.AccountRepository;
import Bot.account.AccountService;
import Bot.insight.InsightEntity;
import Bot.insight.InsightKind;
import Bot.insight.InsightRepository;
import Bot.owner.Owner;
import Bot.processing.JobState;
import Bot.processing.JobStore;
import Bot.processing.ProcessingJob;
import Bot.support.PostgresTestContainer;
import Bot.transcription.TranscriptSegments;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
class TranscriptPageIT {

    private static final String EMAIL = "anya@example.com";
    private static final String PASSWORD = "очень-длинный-пароль";

    @Autowired MockMvc mvc;
    @Autowired AccountService accounts;
    @Autowired AccountRepository accountRepository;
    @Autowired JobStore jobs;
    @Autowired Bot.processing.JobRepository jobRepository;
    @Autowired TranscriptSegments segments;
    @Autowired InsightRepository insightRepository;

    @TempDir Path dir;

    private UUID accountId;
    private MockHttpSession session;

    @BeforeEach
    void signIn() throws Exception {
        accountId = accounts.register(EMAIL, PASSWORD, "Аня").id();
        MvcResult login = mvc.perform(post("/login").with(csrf())
                .param("email", EMAIL)
                .param("password", PASSWORD)).andReturn();
        session = (MockHttpSession) login.getRequest().getSession(false);
    }

    @AfterEach
    void clean() {
        insightRepository.deleteAll();
        jobRepository.deleteAll();
        accountRepository.deleteAll();
    }

    @Test
    void transcriptShowsLinesAndSpeakers() throws Exception {
        UUID jobId = jobWithTranscript();

        mvc.perform(get("/cabinet/transcript/" + jobId).session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Начнём с общего вопроса")))
                .andExpect(content().string(containsString("Спикер 1")))
                .andExpect(content().string(containsString("name=\"_csrf\"")));
    }

    /** Чужая задача обязана выглядеть так же, как несуществующая. */
    @Test
    void someoneElsesTranscriptIsNotShown() throws Exception {
        UUID foreign = jobs.enqueue(
                ProcessingJob.newLink(Owner.telegram(999L), "https://vimeo.com/чужое")).id();

        mvc.perform(get("/cabinet/transcript/" + foreign).session(session))
                .andExpect(redirectedUrl("/cabinet"));
        mvc.perform(get("/cabinet/transcript/" + UUID.randomUUID()).session(session))
                .andExpect(redirectedUrl("/cabinet"));
    }

    @Test
    void editedTextGoesIntoTheDownloadedFile() throws Exception {
        UUID jobId = jobWithTranscript();
        Long firstLine = segments.of(jobId).segments().get(0).getId();

        mvc.perform(post("/cabinet/transcript/" + jobId).session(session).with(csrf())
                        .param("segmentId", String.valueOf(firstLine))
                        .param("text", "Начнём с главного вопроса.")
                        .param("speakerLabel", "SPEAKER_00")
                        .param("speakerName", "Ведущий"))
                .andExpect(redirectedUrl("/cabinet/transcript/" + jobId));

        // Файл отдаётся потоком байтов без указания кодировки — читаем его так же,
        // как читал бы редактор, иначе кириллица сравнивается с мусором
        String downloaded = mvc.perform(get("/cabinet/files/" + jobId + "/txt").session(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        assertThat(downloaded)
                .contains("Начнём с главного вопроса.")
                .contains("Ведущий:")
                .doesNotContain("Начнём с общего вопроса");
    }

    /** Без ответа 206 браузер не умеет перематывать запись. */
    @Test
    void mediaAnswersRangeRequests() throws Exception {
        UUID jobId = jobWithTranscript();

        mvc.perform(get("/cabinet/transcript/" + jobId + "/media").session(session)
                        .header(HttpHeaders.RANGE, "bytes=2-5"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 2-5/11"))
                .andExpect(content().string("2345"));

        mvc.perform(get("/cabinet/transcript/" + jobId + "/media").session(session))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"));
    }

    @Test
    void searchFindsTheLineAndLinksToItsSecond() throws Exception {
        UUID jobId = jobWithTranscript();

        mvc.perform(get("/cabinet/search").param("q", "деменция").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/cabinet/transcript/" + jobId)))
                .andExpect(content().string(containsString("#t=6.0")));
    }

    /**
     * Модели может не быть вовсе — на чужой машине, без видеокарты, без Ollama.
     *
     * <p>Тогда страница про это честно говорит, а заказ вежливо отклоняется.
     * Расшифровка при этом остаётся полностью рабочей.</p>
     */
    @Test
    void withoutAModelThePageSaysSoAndTakesNoOrders() throws Exception {
        UUID jobId = jobWithTranscript();

        mvc.perform(get("/cabinet/transcript/" + jobId).session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Языковая модель сейчас не отвечает")))
                .andExpect(content().string(containsString("Сохранить правки")));

        mvc.perform(post("/cabinet/transcript/" + jobId + "/insight").session(session).with(csrf())
                        .param("kind", "SUMMARY")
                        .param("ratio", "15"))
                .andExpect(redirectedUrl("/cabinet/transcript/" + jobId + "#insights"));
        assertThat(insightRepository.findByJobIdOrderByCreatedAtDesc(jobId)).isEmpty();
    }

    /** Метки времени в ответе модели должны стать кнопками перемотки. */
    @Test
    void readyInsightIsShownWithSeekButtons() throws Exception {
        UUID jobId = jobWithTranscript();
        insight(jobId, "Говорили о деменции [0:06]. А в [1:30:00] — ничего, записи столько нет.");

        String page = mvc.perform(get("/cabinet/transcript/" + jobId).session(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(page)
                .contains("data-seconds=\"6.0\"")
                .contains("Говорили о деменции")
                // Выдуманное время осталось текстом: кнопка вела бы в никуда
                .contains("[1:30:00]");
    }

    @Test
    void insightCanBeRemovedFromTheList() throws Exception {
        UUID jobId = jobWithTranscript();
        long id = insight(jobId, "Короткий пересказ.");

        mvc.perform(post("/cabinet/transcript/" + jobId + "/insight/" + id + "/delete")
                        .session(session).with(csrf()))
                .andExpect(redirectedUrl("/cabinet/transcript/" + jobId + "#insights"));

        assertThat(insightRepository.findById(id)).isEmpty();
    }

    /* ───────── helpers ───────── */

    /** Готовая обработка в базе — так, будто её только что посчитал воркер. */
    private long insight(UUID jobId, String text) {
        InsightEntity insight = new InsightEntity();
        insight.setJobId(jobId);
        insight.setKind(InsightKind.SUMMARY);
        insight.setRatio(15);
        insight.setState(JobState.DONE);
        insight.setText(text);
        insight.setModel("подставная");
        insight.setCreatedAt(java.time.Instant.now());
        return insightRepository.save(insight).getId();
    }

    /** Задача с записью на диске и разметкой — как её оставляет Whisper. */
    private UUID jobWithTranscript() throws IOException {
        Path media = dir.resolve("clip.mp4");
        Files.writeString(media, "0REDACTED_CHAT_ID0");
        Path txt = dir.resolve("clip.txt");
        Files.writeString(txt, "текст");
        Files.writeString(dir.resolve("clip.json"), """
                {"language":"ru","segments":[
                  {"start":0.0,"end":6.0,"text":" Начнём с общего вопроса.","speaker":"SPEAKER_00"},
                  {"start":6.0,"end":9.0,"text":" Как вы относитесь к исследованиям деменции?","speaker":"SPEAKER_01"}
                ]}""");

        UUID jobId = jobs.enqueue(ProcessingJob.newFile(
                Owner.account(accountId.toString()), media)).id();
        jobs.complete(jobId, txt);
        segments.importFrom(jobId, txt);
        return jobId;
    }
}

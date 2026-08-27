package Bot.site;

/**
 * Расшифровка целиком: плеер, текст по репликам и правка.
 *
 * <p>Ответственность: показать расшифровку задачи так, чтобы её можно было
 * читать вместе с записью, поправить ошибку распознавания и назвать голоса.
 * Проверка «моя ли это задача» — та же, что у скачивания файлов
 * ({@link JobHistory#find}): чужой номер обязан выглядеть как несуществующий.</p>
 *
 * <p>Запись отдаётся отсюда же и обязательно с поддержкой Range: без ответов
 * 206 браузер не умеет перематывать, а перемотка — это половина смысла
 * страницы. Файл при этом никуда не копируется.</p>
 */
import Bot.config.Profiles;
import Bot.insight.InsightEntity;
import Bot.insight.InsightKind;
import Bot.insight.InsightService;
import Bot.insight.InsightText;
import Bot.processing.JobEntity;
import Bot.processing.JobState;
import Bot.transcription.Timecode;
import Bot.transcription.Transcript;
import Bot.transcription.TranscriptSegmentEntity;
import Bot.transcription.TranscriptSegments;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.util.StreamUtils;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.io.InputStream;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Profile(Profiles.HOME)
@Controller
@RequestMapping("/cabinet/transcript")
@RequiredArgsConstructor
@Slf4j
public class TranscriptController {

    /** Когда заказали обработку: у списка из нескольких иначе не разобрать порядок. */
    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.systemDefault());

    private final JobHistory history;
    private final TranscriptSegments transcripts;
    private final InsightService insights;

    @GetMapping("/{jobId}")
    public String page(@AuthenticationPrincipal AccountPrincipal principal,
                       @PathVariable String jobId, Model model) {
        JobEntity job = mine(principal, jobId).orElse(null);
        if (job == null) {
            return "redirect:/cabinet";
        }

        Transcript transcript = transcripts.of(job.getId());
        model.addAttribute("account", principal);
        model.addAttribute("jobId", job.getId());
        model.addAttribute("title", titleOf(job));
        model.addAttribute("lines", linesOf(transcript));
        model.addAttribute("speakers", speakerFields(transcript));
        model.addAttribute("hasMedia", mediaOf(job) != null);
        List<Insight> processed = insightsOf(job.getId(), transcript);
        model.addAttribute("insightReady", insights.ready());
        model.addAttribute("insights", processed);
        // Пока что-то считается, страница просит браузер вернуться за ответом:
        // человек заказал обработку и ждёт её здесь же
        model.addAttribute("insightPending", processed.stream().anyMatch(Insight::pending));
        return "transcript";
    }

    /**
     * Сохранение правок: тексты реплик и имена голосов одной формой.
     *
     * <p>Одна форма, а не две: человек правит и то, и другое вперемешку, и
     * второй кнопкой «сохранить» он бы потерял половину работы.</p>
     */
    @PostMapping("/{jobId}")
    public String save(@AuthenticationPrincipal AccountPrincipal principal,
                       @PathVariable String jobId,
                       @RequestParam(required = false) List<Long> segmentId,
                       @RequestParam(required = false) List<String> text,
                       @RequestParam(required = false) List<String> speakerLabel,
                       @RequestParam(required = false) List<String> speakerName,
                       RedirectAttributes redirect) {
        JobEntity job = mine(principal, jobId).orElse(null);
        if (job == null) {
            return "redirect:/cabinet";
        }

        transcripts.renameSpeakers(job.getId(), pairs(speakerLabel, speakerName));
        int changed = transcripts.saveEdits(job.getId(), segmentId, text);

        redirect.addFlashAttribute("message", changed == 0
                ? "Сохранено."
                : "Сохранено, исправлено реплик: " + changed + ".");
        return "redirect:/cabinet/transcript/" + job.getId();
    }

    /**
     * Заказ на обработку текста моделью.
     *
     * <p>Ответа здесь не будет: модель считает минутами, и держать всё это
     * время http-запрос значило бы показывать человеку крутящийся браузер, а
     * при закрытой вкладке — терять уже начатую работу. Заказ ложится в
     * очередь, страница показывает «считается».</p>
     */
    @PostMapping("/{jobId}/insight")
    public String order(@AuthenticationPrincipal AccountPrincipal principal,
                        @PathVariable String jobId,
                        @RequestParam InsightKind kind,
                        @RequestParam(required = false) Integer ratio,
                        @RequestParam(required = false) String topic,
                        RedirectAttributes redirect) {
        JobEntity job = mine(principal, jobId).orElse(null);
        if (job == null) {
            return "redirect:/cabinet";
        }

        Optional<String> refusal = insights.order(job.getId(), kind, ratio, topic);
        redirect.addFlashAttribute(refusal.isPresent() ? "error" : "message",
                refusal.orElse("Отправлено в обработку. Результат появится на этой странице."));
        return "redirect:/cabinet/transcript/" + job.getId() + "#insights";
    }

    /** Убрать посчитанное: список обработок иначе растёт без конца. */
    @PostMapping("/{jobId}/insight/{id}/delete")
    public String forget(@AuthenticationPrincipal AccountPrincipal principal,
                         @PathVariable String jobId,
                         @PathVariable long id) {
        JobEntity job = mine(principal, jobId).orElse(null);
        if (job == null) {
            return "redirect:/cabinet";
        }
        insights.forget(job.getId(), id);
        return "redirect:/cabinet/transcript/" + job.getId() + "#insights";
    }

    /**
     * Сама запись — с поддержкой перемотки.
     *
     * <p>Отдаётся с диска как есть: копия ради плеера означала бы второй
     * гигабайт на каждую задачу.</p>
     *
     * <p>Байты пишутся в ответ вручную, а не отдаются готовым {@code Resource}:
     * преобразователь диапазонов в Spring выбирается по <i>объявленному</i> типу
     * возврата, и метод, который отдаёт то целый файл, то кусок, ему не
     * подходит — на запрос с Range получается 500.</p>
     */
    @GetMapping("/{jobId}/media")
    public void media(@AuthenticationPrincipal AccountPrincipal principal,
                      @PathVariable String jobId,
                      HttpServletRequest request,
                      HttpServletResponse response) throws IOException {
        JobEntity job = mine(principal, jobId).orElse(null);
        Path file = job == null ? null : mediaOf(job);
        if (file == null) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        long length = Files.size(file);
        response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
        response.setContentType(contentTypeOf(file).toString());

        // Первый диапазон, а не все: браузеры при перемотке просят ровно один,
        // а составной ответ multipart/byteranges плееры принимают хуже
        List<HttpRange> ranges = ranges(request);
        if (ranges.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentLengthLong(length);
            try (InputStream in = Files.newInputStream(file)) {
                in.transferTo(response.getOutputStream());
            }
            return;
        }

        HttpRange range = ranges.get(0);
        long start = range.getRangeStart(length);
        long end = range.getRangeEnd(length);
        if (start >= length) {
            // Запрошено за концом файла: по RFC это 416 и указание настоящего
            // размера, чтобы плеер поправился сам
            response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes */" + length);
            response.sendError(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE.value());
            return;
        }

        long count = end - start + 1;
        response.setStatus(HttpStatus.PARTIAL_CONTENT.value());
        response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + length);
        response.setContentLengthLong(count);
        try (InputStream in = Files.newInputStream(file)) {
            in.skipNBytes(start);
            StreamUtils.copyRange(in, response.getOutputStream(), 0, count - 1);
        }
    }

    /* ───────── helpers ───────── */

    private Optional<JobEntity> mine(AccountPrincipal principal, String jobId) {
        try {
            return history.find(principal.accountId(), UUID.fromString(jobId));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** Битый заголовок Range — это не 500: отдаём файл целиком. */
    private static List<HttpRange> ranges(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.RANGE);
        if (header == null || header.isBlank()) {
            return List.of();
        }
        try {
            return HttpRange.parseRanges(header);
        } catch (IllegalArgumentException e) {
            return List.of();
        }
    }

    private static Path mediaOf(JobEntity job) {
        if (job.getFilePath() == null) {
            return null;
        }
        Path file = Path.of(job.getFilePath());
        return Files.isRegularFile(file) ? file : null;
    }

    private static MediaType contentTypeOf(Path file) {
        try {
            String probed = Files.probeContentType(file);
            return probed == null ? MediaType.APPLICATION_OCTET_STREAM : MediaType.parseMediaType(probed);
        } catch (IOException | IllegalArgumentException e) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    private static String titleOf(JobEntity job) {
        if (job.getUrl() != null && !job.getUrl().isBlank()) {
            return job.getUrl();
        }
        return job.getFilePath() == null
                ? "Расшифровка"
                : Path.of(job.getFilePath()).getFileName().toString();
    }

    private static List<Line> linesOf(Transcript transcript) {
        List<Line> lines = new ArrayList<>();
        String previous = null;
        for (TranscriptSegmentEntity segment : transcript.segments()) {
            String speaker = segment.getSpeaker();
            // Имя подписывается только там, где голос сменился: в диалоге
            // подпись у каждой реплики читается хуже самого текста
            String name = speaker != null && !speaker.equals(previous)
                    ? transcript.nameOf(speaker) : null;
            previous = speaker;
            lines.add(new Line(segment.getId(), Timecode.format(segment.getStartMs()),
                    segment.getStartMs() / 1000.0, name, segment.getText(), segment.isEdited()));
        }
        return lines;
    }

    private static List<Speaker> speakerFields(Transcript transcript) {
        List<Speaker> fields = new ArrayList<>();
        transcript.speakers().forEach((label, name) -> fields.add(new Speaker(label, name)));
        return fields;
    }

    private static Map<String, String> pairs(List<String> labels, List<String> names) {
        Map<String, String> result = new HashMap<>();
        if (labels == null || names == null || labels.size() != names.size()) {
            return result;
        }
        for (int i = 0; i < labels.size(); i++) {
            result.put(labels.get(i), names.get(i));
        }
        return result;
    }

    /**
     * Обработки задачи в том виде, в каком их показывает страница.
     *
     * <p>Метки времени в ответе модели сверяются с длиной записи: выдуманное
     * время кнопкой не становится (см. {@link InsightText}).</p>
     */
    private List<Insight> insightsOf(UUID jobId, Transcript transcript) {
        int durationMs = transcript.segments().isEmpty()
                ? 0
                : transcript.segments().get(transcript.segments().size() - 1).getEndMs();

        List<Insight> result = new ArrayList<>();
        for (InsightEntity insight : insights.of(jobId)) {
            result.add(new Insight(
                    insight.getId(),
                    titleOf(insight),
                    stateOf(insight),
                    insight.isPending(),
                    insight.getState() == JobState.FAILED,
                    InsightText.parts(insight.getText(), durationMs),
                    insight.getError(),
                    WHEN.format(insight.getCreatedAt())));
        }
        return result;
    }

    private static String titleOf(InsightEntity insight) {
        return insight.getKind() == InsightKind.SUMMARY
                ? "Выжимка: %d%% от текста".formatted(insight.getRatio())
                : "По теме: «%s»".formatted(insight.getTopic());
    }

    private static String stateOf(InsightEntity insight) {
        return switch (insight.getState()) {
            case QUEUED -> "В очереди";
            case RUNNING -> "Считается";
            case DONE -> "Готово";
            case FAILED -> "Не получилось";
        };
    }

    /** Реплика, как её показывает страница. */
    public record Line(Long id, String at, double seconds, String speaker, String text, boolean edited) {}

    /** Поле «как зовут этот голос». */
    public record Speaker(String label, String name) {}

    /** Обработка текста, как её показывает страница. */
    public record Insight(long id,
                          String title,
                          String state,
                          boolean pending,
                          boolean failed,
                          List<InsightText.Part> parts,
                          String error,
                          String when) {}
}

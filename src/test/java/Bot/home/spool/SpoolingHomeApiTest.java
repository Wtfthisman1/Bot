package Bot.home.spool;

/**
 * Поведение при спящем доме: задачу принять, дождаться пробуждения, отдать.
 */
import Bot.home.HomeApi;
import Bot.home.HomeApi.Acceptance;
import Bot.home.HomeUnavailableException;
import Bot.owner.Owner;
import Bot.processing.MediaKind;
import Bot.telegram.MessageSender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentMatchers;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SpoolingHomeApiTest {

    private static final Owner OWNER = Owner.telegram(77L);
    private static final String URL = "https://youtu.be/x";

    @TempDir Path dir;

    private HomeApi delegate;
    private MessageSender messageSender;
    private TaskSpool spool;
    private SpoolingHomeApi api;

    @BeforeEach
    void setUp() {
        delegate = mock(HomeApi.class);
        messageSender = mock(MessageSender.class);
        spool = new TaskSpool(dir.toString(),
                new ObjectMapper().registerModule(new JavaTimeModule()));
        api = new SpoolingHomeApi(delegate, spool, messageSender, 500, Duration.ofDays(3));
    }

    private void homeIsAsleep() {
        when(delegate.transcribeLink(any(), any()))
                .thenThrow(new HomeUnavailableException("спит", null));
    }

    @Test
    void awakeHomeGetsTheTaskRightAway() {
        when(delegate.transcribeLink(OWNER, URL)).thenReturn(Acceptance.STARTED);

        assertThat(api.transcribeLink(OWNER, URL)).isEqualTo(Acceptance.STARTED);
        assertThat(spool.pending()).isEmpty();
    }

    @Test
    void sleepingHomeMakesTheTaskDeferredInsteadOfLost() {
        homeIsAsleep();

        assertThat(api.transcribeLink(OWNER, URL)).isEqualTo(Acceptance.DEFERRED);
        assertThat(spool.pending()).singleElement()
                .satisfies(task -> assertThat(task.url()).isEqualTo(URL));
    }

    /** Пока спул не пуст, свежая задача не должна обгонять отложенную. */
    @Test
    void freshTaskDoesNotOvertakeTheWaitingOne() {
        homeIsAsleep();
        api.transcribeLink(OWNER, "ночная");

        // Дом проснулся, но очередь ещё не разобрана.
        // doReturn, а не when(...): метод уже настроен бросать, и обычная
        // перенастройка вызвала бы это исключение прямо здесь
        doReturn(Acceptance.STARTED).when(delegate).transcribeLink(any(), any());
        assertThat(api.transcribeLink(OWNER, "утренняя")).isEqualTo(Acceptance.DEFERRED);

        assertThat(spool.pending()).extracting(SpooledTask::url)
                .containsExactly("ночная", "утренняя");
    }

    @Test
    void wakingHomeGetsEverythingInOrderAndOwnerIsTold() {
        homeIsAsleep();
        api.transcribeLink(OWNER, "первая");
        doThrow(new HomeUnavailableException("спит", null))
                .when(delegate).downloadLink(any(), any(), any());
        api.downloadLink(OWNER, "вторая", MediaKind.AUDIO);

        doReturn(Acceptance.STARTED).when(delegate).transcribeLink(any(), any());
        doReturn(Acceptance.STARTED).when(delegate).downloadLink(any(), any(), any());
        // Приём уже разок постучался домой и получил отказ — считаем только то,
        // что уходит при разборе спула
        clearInvocations(delegate);
        api.flush();

        verify(delegate).transcribeLink(OWNER, "первая");
        verify(delegate).downloadLink(OWNER, "вторая", MediaKind.AUDIO);
        assertThat(spool.pending()).isEmpty();
        verify(messageSender).sendMessageWithKeyboard(eq(77L), contains("проснулась"), any(), any());
    }

    /** Дом всё ещё спит — обход прекращается на первой же задаче. */
    @Test
    void stillSleepingHomeKeepsTheSpoolIntact() {
        homeIsAsleep();
        api.transcribeLink(OWNER, "первая");
        api.transcribeLink(OWNER, "вторая");

        api.flush();

        assertThat(spool.pending()).hasSize(2);
        verify(messageSender, never()).sendMessageWithKeyboard(anyLong(), any(), any(), any());
    }

    /** Ошибка дома повторяется несколько раз, потом задача снимается с объяснением. */
    @Test
    void repeatedFailureEndsWithAnHonestRefusal() {
        homeIsAsleep();
        api.transcribeLink(OWNER, URL);

        doThrow(new IllegalStateException("сломалось"))
                .when(delegate).transcribeLink(any(), any());
        clearInvocations(delegate);
        for (int attempt = 0; attempt < 5; attempt++) {
            api.flush();
        }

        assertThat(spool.pending()).isEmpty();
        verify(delegate, times(5)).transcribeLink(OWNER, URL);
        verify(messageSender).sendMessageWithKeyboard(eq(77L), contains("Не получилось"), any(), any());
    }

    @Test
    void overflowingSpoolRefusesInsteadOfGrowing() {
        SpoolingHomeApi tiny = new SpoolingHomeApi(delegate, spool, messageSender,
                1, Duration.ofDays(3));
        homeIsAsleep();
        tiny.transcribeLink(OWNER, "первая");

        assertThatThrownBy(() -> tiny.transcribeLink(OWNER, "вторая"))
                .isInstanceOf(HomeUnavailableException.class);
        assertThat(spool.pending()).hasSize(1);
    }

    /** Сводка при спящем доме собирается из спула, а не падает. */
    @Test
    void statusFallsBackToTheSpool() {
        homeIsAsleep();
        api.transcribeLink(OWNER, URL);
        when(delegate.status(OWNER)).thenThrow(new HomeUnavailableException("спит", null));

        HomeApi.OwnerStatus status = api.status(OWNER);

        assertThat(status.queued()).isEqualTo(1);
        assertThat(status.downloads()).isEmpty();
    }

    /** Отложенные задачи видны в сводке вместе с теми, что уже в очереди дома. */
    @Test
    void statusAddsDeferredToWhatHomeReports() {
        homeIsAsleep();
        api.transcribeLink(OWNER, URL);
        when(delegate.status(OWNER)).thenReturn(new HomeApi.OwnerStatus(2, 1, List.of()));

        assertThat(api.status(OWNER).queued()).isEqualTo(3);
    }

    /** Ссылку на форму отложить нельзя: форму отдаёт дом. */
    @Test
    void uploadFormLinkIsNotDeferred() {
        when(delegate.uploadFormLink(OWNER)).thenThrow(new HomeUnavailableException("спит", null));

        assertThatThrownBy(() -> api.uploadFormLink(OWNER))
                .isInstanceOf(HomeUnavailableException.class);
        assertThat(spool.pending()).isEmpty();
    }

    /** Отказ по размеру не повод откладывать: через час файл меньше не станет. */
    @Test
    void tooLargeFileIsNotSpooled() throws Exception {
        HomeApi.TelegramFile file =
                new HomeApi.TelegramFile("id", "big.mp4", HomeApi.TelegramFile.Kind.VIDEO);
        doThrow(new Bot.telegram.FileTooLargeException("слишком большой"))
                .when(delegate).transcribeTelegramFile(ArgumentMatchers.eq(OWNER), any());

        assertThatThrownBy(() -> api.transcribeTelegramFile(OWNER, file))
                .isInstanceOf(Bot.telegram.FileTooLargeException.class);
        assertThat(spool.pending()).isEmpty();
    }
}

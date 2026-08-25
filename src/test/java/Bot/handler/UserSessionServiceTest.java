package Bot.handler;

import Bot.handler.UserSessionService.Mode;
import Bot.handler.UserSessionService.Pending;
import Bot.processing.MediaKind;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UserSessionServiceTest {

    private static final long CHAT = 42L;

    private final UserSessionService sessions = new UserSessionService();

    @Test
    void awaitedModeIsReturnedOnceAndThenCleared() {
        sessions.awaitLink(CHAT, Mode.DOWNLOAD, MediaKind.VIDEO);

        assertThat(sessions.isAwaitingLink(CHAT)).isTrue();
        assertThat(sessions.takeAwaiting(CHAT))
                .contains(new Pending(Mode.DOWNLOAD, MediaKind.VIDEO));
        assertThat(sessions.takeAwaiting(CHAT)).isEmpty();
        assertThat(sessions.isAwaitingLink(CHAT)).isFalse();
    }

    /** Формат — часть выбора: аудио и видео не должны схлопываться в одно состояние. */
    @Test
    void mediaKindIsCarriedAlongWithTheMode() {
        sessions.awaitLink(CHAT, Mode.DOWNLOAD, MediaKind.AUDIO);
        assertThat(sessions.takeAwaiting(CHAT))
                .contains(new Pending(Mode.DOWNLOAD, MediaKind.AUDIO));
    }

    @Test
    void pendingUrlIsReturnedOnceAndThenCleared() {
        sessions.rememberUrl(CHAT, "https://youtu.be/x");

        assertThat(sessions.isAwaitingLink(CHAT)).isFalse();
        assertThat(sessions.takePendingUrl(CHAT)).contains("https://youtu.be/x");
        assertThat(sessions.takePendingUrl(CHAT)).isEmpty();
    }

    @Test
    void statesDoNotLeakIntoEachOther() {
        sessions.awaitLink(CHAT, Mode.TRANSCRIBE, MediaKind.AUDIO);
        assertThat(sessions.takePendingUrl(CHAT)).isEmpty();

        sessions.rememberUrl(CHAT, "https://youtu.be/x");
        assertThat(sessions.takeAwaiting(CHAT)).isEmpty();
    }

    @Test
    void statesAreIsolatedPerChat() {
        sessions.awaitLink(1L, Mode.TRANSCRIBE, MediaKind.AUDIO);
        sessions.awaitLink(2L, Mode.DOWNLOAD, MediaKind.VIDEO);

        assertThat(sessions.takeAwaiting(1L)).contains(new Pending(Mode.TRANSCRIBE, MediaKind.AUDIO));
        assertThat(sessions.takeAwaiting(2L)).contains(new Pending(Mode.DOWNLOAD, MediaKind.VIDEO));
    }

    @Test
    void clearResetsEverything() {
        sessions.awaitLink(CHAT, Mode.DOWNLOAD, MediaKind.VIDEO);
        sessions.clear(CHAT);

        assertThat(sessions.isAwaitingLink(CHAT)).isFalse();
        assertThat(sessions.takeAwaiting(CHAT)).isEmpty();
    }
}

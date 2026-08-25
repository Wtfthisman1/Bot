package Bot.processing;

/**
 * Что именно тянуть с площадки.
 *
 * <p>Для транскрипции всегда {@link #AUDIO}: Whisper использует только звук, а
 * полное видео того же ролика весит на порядок больше (628 МБ против ~50 МБ на
 * часовом интервью) и дольше качается. Для команды «Скачать» вид выбирает
 * пользователь кнопкой.</p>
 */
public enum MediaKind {

    AUDIO(".m4a", "audio", "🎵 Аудио"),
    VIDEO(".mp4", "video", "🎬 Видео");

    private final String extension;
    /** Значение аргумента mode для Downloader.py. */
    private final String scriptMode;
    private final String title;

    MediaKind(String extension, String scriptMode, String title) {
        this.extension = extension;
        this.scriptMode = scriptMode;
        this.title = title;
    }

    public String extension() {
        return extension;
    }

    public String scriptMode() {
        return scriptMode;
    }

    public String title() {
        return title;
    }
}

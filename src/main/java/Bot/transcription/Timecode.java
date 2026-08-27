package Bot.transcription;

/**
 * Время внутри записи: {@code 2:03} у коротких, {@code 1:02:03} у длинных.
 *
 * <p>Отдельный класс, потому что этот формат читают и пишут в трёх местах:
 * страница расшифровки, поиск и обработка текста моделью. В последней его ещё
 * и разбирают обратно — модель проставляет метки в ответе, и по ним страница
 * перематывает плеер.</p>
 *
 * <p>Час без ведущего нуля — не небрежность: так подписывает время YouTube, и
 * это единственный формат, который человек читает не задумываясь.</p>
 */
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Timecode {

    /** {@code 1:02:03} и {@code 2:03}; часы необязательны. */
    private static final Pattern CLOCK = Pattern.compile("(?:(\\d{1,2}):)?(\\d{1,2}):(\\d{2})");

    private Timecode() {
    }

    public static String format(int millis) {
        int seconds = Math.max(0, millis) / 1000;
        return seconds >= 3600
                ? String.format("%d:%02d:%02d", seconds / 3600, (seconds % 3600) / 60, seconds % 60)
                : String.format("%d:%02d", seconds / 60, seconds % 60);
    }

    /**
     * Разбирает метку времени обратно в миллисекунды.
     *
     * <p>Пусто, если это не время: разбирается то, что написала языковая модель,
     * и «[примерно тут]» в ответе — обычное дело.</p>
     */
    public static OptionalInt parse(String text) {
        if (text == null) {
            return OptionalInt.empty();
        }
        Matcher matcher = CLOCK.matcher(text.strip());
        if (!matcher.matches()) {
            return OptionalInt.empty();
        }
        int hours = matcher.group(1) == null ? 0 : Integer.parseInt(matcher.group(1));
        int minutes = Integer.parseInt(matcher.group(2));
        int seconds = Integer.parseInt(matcher.group(3));
        if (seconds > 59 || (matcher.group(1) != null && minutes > 59)) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(((hours * 60 + minutes) * 60 + seconds) * 1000);
    }

    /** Шаблон метки — нужен тем, кто ищет метки внутри текста. */
    public static Pattern pattern() {
        return CLOCK;
    }
}

package Bot.insight;

/**
 * Ответ модели, разобранный для показа.
 *
 * <p>Ответственность: найти в тексте метки времени и превратить их в кнопки
 * перемотки, оставив всё прочее обычным текстом. Разбор здесь, а не в шаблоне,
 * по той же причине, что и у поиска: шаблон умеет только показывать, а в
 * страницу не должен попадать HTML, собранный где-то ещё.</p>
 *
 * <p>Метка признаётся только если такое время в записи есть. Модель, которую
 * попросили сослаться на время, иногда его выдумывает — «[1:12:40]» в получасовой
 * записи. Кнопка на такую метку перематывала бы в никуда, поэтому она остаётся
 * просто текстом: пусть человек видит, что модель ошиблась, а не тычет в мёртвую
 * ссылку.</p>
 */
import Bot.transcription.Timecode;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class InsightText {

    /** Метка времени в ответе: то, что стоит в квадратных скобках. */
    private static final Pattern BRACKETED = Pattern.compile("\\[([^\\[\\]\n]{1,20})]");

    /**
     * Запас на конце: последняя реплика может обрываться раньше, чем звук,
     * и ссылка на самый конец записи — не ошибка модели.
     */
    private static final int TAIL_MS = 5_000;

    private InsightText() {
    }

    public static List<Part> parts(String answer, int durationMs) {
        List<Part> parts = new ArrayList<>();
        if (answer == null || answer.isBlank()) {
            return parts;
        }

        Matcher matcher = BRACKETED.matcher(answer);
        int position = 0;
        while (matcher.find()) {
            OptionalInt millis = Timecode.parse(matcher.group(1));
            if (millis.isEmpty() || millis.getAsInt() > durationMs + TAIL_MS) {
                continue;   // не время или время, которого в записи нет
            }
            if (matcher.start() > position) {
                parts.add(Part.text(answer.substring(position, matcher.start())));
            }
            parts.add(Part.at(millis.getAsInt()));
            position = matcher.end();
        }
        if (position < answer.length()) {
            parts.add(Part.text(answer.substring(position)));
        }
        return parts;
    }

    /**
     * Кусок ответа: либо текст, либо метка времени.
     *
     * <p>{@code seconds} не {@code null} ровно тогда, когда это метка — шаблон
     * по этому и выбирает, рисовать абзац или кнопку.</p>
     */
    public record Part(String text, Double seconds) {

        static Part text(String text) {
            return new Part(text, null);
        }

        static Part at(int millis) {
            return new Part(Timecode.format(millis), millis / 1000.0);
        }
    }
}

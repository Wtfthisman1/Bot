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
 *
 * <p>Скобки модель иногда теряет и пишет время просто в начале строки — этого
 * её не отучить ни одной формулировкой, проверено. Поэтому метка в начале строки
 * принимается и без скобок. В середине предложения — только в скобках: «созвон
 * в 14:30» это не место в записи, и кнопка там была бы вредна.</p>
 */
import Bot.transcription.Timecode;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class InsightText {

    /**
     * Метка времени в ответе: в скобках где угодно или без скобок в начале строки
     * (со списочным дефисом или без него).
     */
    private static final Pattern MARK = Pattern.compile(
            "\\[([^\\[\\]\n]{1,20})]|^[ \t]*(?:[-–—*]\\s*)?((?:\\d{1,2}:)?\\d{1,2}:\\d{2})",
            Pattern.MULTILINE);

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

        Matcher matcher = MARK.matcher(answer);
        int position = 0;
        while (matcher.find()) {
            boolean bracketed = matcher.group(1) != null;
            OptionalInt millis = Timecode.parse(bracketed ? matcher.group(1) : matcher.group(2));
            if (millis.isEmpty() || millis.getAsInt() > durationMs + TAIL_MS) {
                continue;   // не время или время, которого в записи нет
            }
            // Дефис списка и отступ перед меткой — часть строки, а не метки:
            // проглотив их, кнопка съела бы разметку списка
            int start = bracketed ? matcher.start() : matcher.end() - matcher.group(2).length();
            if (start > position) {
                parts.add(Part.text(answer.substring(position, start)));
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

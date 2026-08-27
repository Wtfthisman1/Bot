package Bot.insight;

/**
 * Ответ модели, разобранный на текст и кнопки перемотки.
 *
 * <p>Главное здесь — выдуманное время. Модель ссылается на «[1:12:40]» в
 * получасовой записи легко и охотно, и кнопка на такую метку вела бы в никуда.</p>
 */
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InsightTextTest {

    private static final int HALF_HOUR = 30 * 60 * 1000;

    @Test
    void timecodesBecomeButtons() {
        List<InsightText.Part> parts = InsightText.parts("Про сроки говорят [12:30] и позже.", HALF_HOUR);

        assertThat(parts).extracting(InsightText.Part::text)
                .containsExactly("Про сроки говорят ", "12:30", " и позже.");
        assertThat(parts.get(1).seconds()).isEqualTo(750.0);
        assertThat(parts.get(0).seconds()).isNull();
    }

    @Test
    void inventedTimeStaysPlainText() {
        List<InsightText.Part> parts = InsightText.parts("Об этом сказано в [1:12:40].", HALF_HOUR);

        assertThat(parts).hasSize(1);
        assertThat(parts.get(0).seconds()).isNull();
        assertThat(parts.get(0).text()).isEqualTo("Об этом сказано в [1:12:40].");
    }

    /** Диапазон вместо метки — перематываем к его началу. */
    @Test
    void aRangeSeeksToItsStart() {
        List<InsightText.Part> parts = InsightText.parts("[0:19-0:52] Про нейропластичность.", HALF_HOUR);

        assertThat(parts.get(0).seconds()).isEqualTo(19.0);
        assertThat(parts.get(0).text()).isEqualTo("0:19");
    }

    @Test
    void bracketsThatAreNotTimeAreLeftAlone() {
        List<InsightText.Part> parts = InsightText.parts("Ведущий [неразборчиво] продолжает.", HALF_HOUR);

        assertThat(parts).hasSize(1);
        assertThat(parts.get(0).text()).isEqualTo("Ведущий [неразборчиво] продолжает.");
    }

    @Test
    void hoursAreUnderstood() {
        List<InsightText.Part> parts = InsightText.parts("[1:02:03] Итог.", 2 * 60 * 60 * 1000);

        assertThat(parts.get(0).seconds()).isEqualTo(3723.0);
        assertThat(parts.get(0).text()).isEqualTo("1:02:03");
    }

    /** Скобки модель теряет и пишет время просто в начале строки — это тоже метка. */
    @Test
    void timeAtTheStartOfALineCountsWithoutBrackets() {
        List<InsightText.Part> parts = InsightText.parts("- 2:15 Про сроки.\n3:40 Про деньги.", HALF_HOUR);

        assertThat(parts).extracting(InsightText.Part::seconds)
                .containsExactly(null, 135.0, null, 220.0, null);
        assertThat(parts.get(0).text()).isEqualTo("- ");
    }

    /** А вот время посреди фразы — это время встречи, а не место в записи. */
    @Test
    void timeInsideASentenceIsNotASeekButton() {
        List<InsightText.Part> parts = InsightText.parts("Договорились созвониться в 14:30.", HALF_HOUR);

        assertThat(parts).hasSize(1);
        assertThat(parts.get(0).seconds()).isNull();
    }

    @Test
    void nothingToShowWhenTheAnswerIsEmpty() {
        assertThat(InsightText.parts(null, HALF_HOUR)).isEmpty();
        assertThat(InsightText.parts("   ", HALF_HOUR)).isEmpty();
    }
}

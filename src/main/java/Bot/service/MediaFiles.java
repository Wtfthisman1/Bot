package Bot.service;

/**
 * Что мы вообще беремся расшифровывать.
 *
 * <p>Ответственность: одно место, где перечислены расширения аудио и видео.
 * Список нужен трём дверям — чату, форме загрузки и кабинету, — и раньше он
 * был только у чата: через форму и через кабинет на диск ложился любой файл
 * какого угодно размера, а в очередь уходила задача, которой нечего делать.</p>
 *
 * <p>Проверка по имени, а не по содержимому: определять формат по первым
 * байтам всё равно пришлось бы через ffmpeg, то есть уже после того, как файл
 * целиком лёг на диск, — а отказать надо раньше. От опечатки в расширении это
 * не спасёт, зато отсекает то, ради чего форму и стали бы дёргать: архивы,
 * образы и прочий мусор на пару гигабайт.</p>
 */
import java.util.List;
import java.util.Locale;

public final class MediaFiles {

    /** Расширения, которые ffmpeg и Whisper разбирают без уговоров. */
    private static final List<String> EXTENSIONS = List.of(
            // видео
            ".mp4", ".m4v", ".avi", ".mkv", ".mov", ".webm", ".mpeg", ".mpg",
            ".ts", ".flv", ".3gp", ".wmv",
            // аудио
            ".mp3", ".wav", ".m4a", ".ogg", ".oga", ".opus", ".flac", ".aac",
            ".wma", ".amr", ".aiff", ".aif");

    private MediaFiles() {
    }

    /** Похоже ли это на запись, которую есть смысл расшифровывать. */
    public static boolean isMedia(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return false;
        }
        String lower = fileName.toLowerCase(Locale.ROOT).strip();
        return EXTENSIONS.stream().anyMatch(lower::endsWith);
    }

    /** Человекочитаемый список — для отказа, который иначе выглядит придиркой. */
    public static String supportedListText() {
        return "Подойдут аудио и видео: mp4, mkv, mov, avi, webm, mp3, wav, m4a, "
                + "ogg, opus, flac, aac.";
    }
}

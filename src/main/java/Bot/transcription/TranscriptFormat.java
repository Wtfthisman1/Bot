package Bot.transcription;

/**
 * Форматы, в которых пользователь может забрать готовую расшифровку.
 *
 * <p>Ответственность: связать формат с расширением файла, кодом для
 * {@code callback_data} и подписями в интерфейсе — чтобы эти три вещи не
 * разъезжались по разным классам. Whisper за один проход кладёт рядом с
 * {@code .txt} ещё {@code .srt}, {@code .vtt}, {@code .json} и {@code .tsv},
 * поэтому субтитры достаются бесплатно; {@code .docx} собирается из текста
 * по требованию ({@link WordExporter}).</p>
 *
 * <p>Код формата короткий сознательно: он едет в {@code callback_data}, где
 * лимит Telegram — 64 байта на всю строку вместе с идентификатором.</p>
 */
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;

public enum TranscriptFormat {

    TXT (".txt",  "txt", "📄 Текст",         "📄 Расшифровка текстом"),
    SRT (".srt",  "srt", "🎬 Субтитры SRT",  "🎬 Субтитры SRT — для видеоредакторов и плееров"),
    VTT (".vtt",  "vtt", "🌐 Субтитры VTT",  "🌐 Субтитры VTT — для веб-плееров и YouTube"),
    DOCX(".docx", "doc", "📝 Word",          "📝 Расшифровка в Word");

    private final String extension;
    private final String code;
    private final String buttonLabel;
    private final String caption;

    TranscriptFormat(String extension, String code, String buttonLabel, String caption) {
        this.extension = extension;
        this.code = code;
        this.buttonLabel = buttonLabel;
        this.caption = caption;
    }

    public String extension()   { return extension; }
    public String code()        { return code; }
    public String buttonLabel() { return buttonLabel; }
    public String caption()     { return caption; }

    /**
     * Путь к файлу этого формата рядом с исходным {@code .txt}.
     *
     * <p>Whisper кладёт все форматы под одним именем, поэтому достаточно
     * заменить расширение. Точки внутри имени (а они там есть: файлы приходят
     * с площадок) не мешают — отрезается только последнее расширение.</p>
     */
    public Path fileFor(Path txt) {
        String name = txt.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        return txt.resolveSibling(stem + extension);
    }

    /** Разбор кода из {@code callback_data}; неизвестный код — не повод падать. */
    public static Optional<TranscriptFormat> fromCode(String code) {
        return Arrays.stream(values())
                .filter(f -> f.code.equals(code))
                .findFirst();
    }
}

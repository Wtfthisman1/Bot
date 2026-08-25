package Bot.transcription;

/**
 * Сборка {@code .docx} из готового текста расшифровки.
 *
 * <p>Ответственность: превратить {@code .txt} Whisper в документ Word и
 * положить его рядом под тем же именем. Строка вывода Whisper — это реплика,
 * поэтому каждая становится абзацем: сплошной простыней расшифровку неудобно
 * читать и править, а именно ради правки её и открывают в Word.</p>
 *
 * <p>Документ собирается по требованию — при нажатии кнопки, а не после каждой
 * транскрипции: большинству нужен обычный текст, а Word спрашивают редко.
 * Готовый файл переиспользуется, пока не устарел относительно {@code .txt}.</p>
 */
import Bot.config.Profiles;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

@Profile(Profiles.HOME)
@Service
@Slf4j
public class WordExporter {

    private static final String FONT = "Calibri";
    private static final int TITLE_SIZE = 16;
    private static final int BODY_SIZE = 12;

    /**
     * Возвращает путь к {@code .docx} с этой расшифровкой, собирая документ
     * при первом обращении.
     *
     * @param txt текст расшифровки, который отдал Whisper
     */
    public Path export(Path txt) throws IOException {
        Path docx = TranscriptFormat.DOCX.fileFor(txt);

        if (isFresh(docx, txt)) {
            log.debug("Word-документ уже собран, переиспользую: {}", docx.getFileName());
            return docx;
        }

        long startedAt = System.currentTimeMillis();
        // Собираем во временный файл рядом: если два нажатия кнопки придут разом,
        // читатель не увидит наполовину записанный документ
        Path tmp = Files.createTempFile(docx.getParent(), ".docx-", ".part");
        try (XWPFDocument doc = new XWPFDocument()) {
            writeTitle(doc, stem(txt));

            int paragraphs = 0;
            try (BufferedReader reader = Files.newBufferedReader(txt, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) continue;
                    writeBody(doc, line.strip());
                    paragraphs++;
                }
            }

            try (OutputStream out = Files.newOutputStream(tmp)) {
                doc.write(out);
            }
            move(tmp, docx);
            log.info("Word-документ собран: файл={}, абзацев={}, заняло {} мс",
                    docx.getFileName(), paragraphs, System.currentTimeMillis() - startedAt);
            return docx;
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /* ───────── helpers ───────── */

    /** Документ годен, пока он не старше текста, из которого собран. */
    private boolean isFresh(Path docx, Path txt) throws IOException {
        return Files.isRegularFile(docx)
                && Files.getLastModifiedTime(docx).compareTo(Files.getLastModifiedTime(txt)) >= 0;
    }

    private void writeTitle(XWPFDocument doc, String title) {
        XWPFParagraph p = doc.createParagraph();
        p.setAlignment(ParagraphAlignment.CENTER);
        p.setSpacingAfter(240);          // 12 pt в двадцатых долях пункта
        XWPFRun run = p.createRun();
        run.setText(title);
        run.setBold(true);
        run.setFontFamily(FONT);
        run.setFontSize(TITLE_SIZE);
    }

    private void writeBody(XWPFDocument doc, String text) {
        XWPFParagraph p = doc.createParagraph();
        p.setSpacingAfter(120);          // 6 pt: реплики не слипаются
        XWPFRun run = p.createRun();
        run.setText(text);
        run.setFontFamily(FONT);
        run.setFontSize(BODY_SIZE);
    }

    private String stem(Path txt) {
        String name = txt.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /**
     * Атомарная замена там, где файловая система это умеет. На тех, что не
     * умеют, обычное перемещение с заменой — хуже, чем атомарное, но лучше,
     * чем упасть на последнем шаге.
     */
    private void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}

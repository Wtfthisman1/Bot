package Bot.transcription;

import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Сборка .docx: документ ложится рядом с текстом, открывается как Word-файл
 * и не пересобирается зря.
 */
class WordExporterTest {

    @TempDir Path tmp;

    private final WordExporter exporter = new WordExporter();

    @Test
    void buildsReadableDocumentNextToTheText() throws IOException {
        Path txt = Files.writeString(tmp.resolve("интервью.txt"),
                "Первая реплика.\n\nВторая реплика.\n");

        Path docx = exporter.export(txt);

        assertThat(docx).isEqualTo(tmp.resolve("интервью.docx")).exists();
        assertThat(extractText(docx))
                .contains("Первая реплика.")
                .contains("Вторая реплика.")
                .contains("интервью");           // заголовок — имя файла
    }

    /** Пустые строки Whisper разделяют реплики, а не создают пустые абзацы. */
    @Test
    void blankLinesDoNotBecomeParagraphs() throws IOException {
        Path txt = Files.writeString(tmp.resolve("t.txt"), "одна\n\n\nдве\n");

        try (InputStream in = Files.newInputStream(exporter.export(txt));
             XWPFDocument doc = new XWPFDocument(in)) {
            // заголовок + две реплики
            assertThat(doc.getParagraphs()).hasSize(3);
        }
    }

    @Test
    void readyDocumentIsReused() throws IOException {
        Path txt = Files.writeString(tmp.resolve("t.txt"), "текст");

        Path first = exporter.export(txt);
        var stamp = Files.getLastModifiedTime(first);
        Path second = exporter.export(txt);

        assertThat(second).isEqualTo(first);
        assertThat(Files.getLastModifiedTime(second)).isEqualTo(stamp);
    }

    /** Текст перезаписали — старый документ отдавать нельзя. */
    @Test
    void staleDocumentIsRebuilt() throws IOException {
        Path txt = Files.writeString(tmp.resolve("t.txt"), "старое");
        Path docx = exporter.export(txt);
        Files.setLastModifiedTime(docx,
                java.nio.file.attribute.FileTime.fromMillis(
                        Files.getLastModifiedTime(txt).toMillis() - 60_000));

        Files.writeString(txt, "новое");

        assertThat(extractText(exporter.export(txt))).contains("новое");
    }

    /** После сборки не должно оставаться .part-огрызков. */
    @Test
    void temporaryFilesAreCleanedUp() throws IOException {
        Path txt = Files.writeString(tmp.resolve("t.txt"), "текст");

        exporter.export(txt);

        try (var files = Files.list(tmp)) {
            assertThat(files.map(p -> p.getFileName().toString()))
                    .noneMatch(name -> name.endsWith(".part"));
        }
    }

    private String extractText(Path docx) throws IOException {
        try (InputStream in = Files.newInputStream(docx);
             XWPFDocument doc = new XWPFDocument(in);
             XWPFWordExtractor extractor = new XWPFWordExtractor(doc)) {
            return extractor.getText();
        }
    }
}

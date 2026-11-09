package com.filekit;

import com.filekit.cli.Main;
import com.filekit.core.ConversionOptions;
import com.filekit.core.Converter;
import com.filekit.core.batch.BatchConverter;
import com.filekit.core.batch.BatchResult;
import com.filekit.core.batch.ConversionJob;
import com.filekit.core.batch.ConversionListener;
import com.filekit.core.batch.JobPlanner;
import com.filekit.format.Format;
import com.filekit.io.ConversionException;
import com.filekit.io.PageRange;
import com.filekit.io.WriteOptions;
import com.filekit.io.docx.DocxDocumentWriter;
import com.filekit.io.pdf.PdfDocumentReader;
import com.filekit.io.pdf.PdfDocumentWriter;
import com.filekit.model.Document;
import com.filekit.model.Paragraph;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class FeaturesTest {

    @TempDir
    Path dir;

    private final Converter converter = new Converter();

    private static Document pages(int n) {
        Document doc = new Document();
        for (int i = 1; i <= n; i++) {
            doc.newPage().add(Paragraph.body("Page number " + i));
        }
        return doc;
    }

    // ---- Unicode -------------------------------------------------------------

    @Test
    void pdfKeepsNonLatinTextWithBundledFont() {
        String text = "Привет мир — Ελληνικά — Ćevapi, naïve café, ŁÓDŹ → ✓";
        Document doc = new Document();
        doc.newPage().add(Paragraph.heading("Ünïcödé")).add(Paragraph.body(text));

        Path pdf = dir.resolve("u.pdf");
        new PdfDocumentWriter().write(doc, pdf);
        String back = new PdfDocumentReader().read(pdf).plainText();

        assertTrue(back.contains("Привет мир"), back);
        assertTrue(back.contains("Ελληνικά"), back);
        assertTrue(back.contains("ŁÓDŹ → ✓"), back);
        assertFalse(back.contains("?"), back);
    }

    @Test
    void pdfUsesFallbackFontForCjk() {
        Path cjk = Path.of("/usr/share/fonts/truetype/droid/DroidSansFallbackFull.ttf");
        assumeTrue(Files.exists(cjk), "CJK fallback font not installed");

        Document doc = new Document();
        doc.newPage().add(Paragraph.body("Hello 你好世界 こんにちは"));
        Path pdf = dir.resolve("cjk.pdf");
        new PdfDocumentWriter().write(doc, pdf, new WriteOptions(150, 0.9f, List.of(cjk)));

        String back = new PdfDocumentReader().read(pdf).plainText();
        assertTrue(back.contains("你好世界"), back);
        assertTrue(back.contains("こんにちは"), back);
    }

    @Test
    void missingGlyphsBecomeQuestionMarksInsteadOfFailing() {
        Document doc = new Document();
        doc.newPage().add(Paragraph.body("ok 😀 ok")); // emoji: no glyph in DejaVu Sans
        Path pdf = dir.resolve("e.pdf");
        assertDoesNotThrow(() -> new PdfDocumentWriter().write(doc, pdf));
    }

    @Test
    void badFontFileGivesClearError() {
        Document doc = pages(1);
        WriteOptions opts = new WriteOptions(150, 0.9f, List.of(dir.resolve("nope.ttf")));
        ConversionException e = assertThrows(ConversionException.class,
                () -> new PdfDocumentWriter().write(doc, dir.resolve("x.pdf"), opts));
        assertTrue(e.getMessage().contains("nope.ttf"));
    }

    // ---- Page ranges ---------------------------------------------------------

    @Test
    void pageRangeParsing() {
        PageRange r = PageRange.parse("1, 3-4, 7-");
        assertTrue(r.includes(1));
        assertFalse(r.includes(2));
        assertTrue(r.includes(4));
        assertFalse(r.includes(6));
        assertTrue(r.includes(1000));
        assertTrue(PageRange.parse("all").isAll());
        assertThrows(IllegalArgumentException.class, () -> PageRange.parse("5-2"));
        assertThrows(IllegalArgumentException.class, () -> PageRange.parse("abc"));
        assertThrows(IllegalArgumentException.class, () -> PageRange.parse("0"));
    }

    @Test
    void pdfToPngOnlyRendersSelectedPages() {
        Path pdf = dir.resolve("five.pdf");
        new PdfDocumentWriter().write(pages(5), pdf);

        ConversionOptions opts = new ConversionOptions(72, PageRange.parse("2,4"), 0.9f, List.of());
        List<Path> out = converter.convert(pdf, dir.resolve("p.png"), opts);
        assertEquals(2, out.size());

        ConversionOptions txtOpts = new ConversionOptions(150, PageRange.parse("4"), 0.9f, List.of());
        converter.convert(pdf, dir.resolve("p4.txt"), txtOpts);
        assertEquals("Page number 4", assertDoesNotThrow(() -> Files.readString(dir.resolve("p4.txt")).strip()));
    }

    @Test
    void pageRangeOutsideDocumentFails() {
        Path pdf = dir.resolve("two.pdf");
        new PdfDocumentWriter().write(pages(2), pdf);
        ConversionOptions opts = new ConversionOptions(150, PageRange.parse("9"), 0.9f, List.of());
        ConversionException e = assertThrows(ConversionException.class,
                () -> converter.convert(pdf, dir.resolve("x.txt"), opts));
        assertTrue(e.getMessage().contains("2 pages"), e.getMessage());
    }

    // ---- Image options -------------------------------------------------------

    @Test
    void jpegQualityAndDpiChangeOutput() throws Exception {
        Path pdf = dir.resolve("q.pdf");
        new PdfDocumentWriter().write(pages(1), pdf);

        Path low = dir.resolve("low.jpg");
        Path high = dir.resolve("high.jpg");
        converter.convert(pdf, low, new ConversionOptions(150, PageRange.ALL, 0.1f, List.of()));
        converter.convert(pdf, high, new ConversionOptions(150, PageRange.ALL, 1.0f, List.of()));
        assertTrue(Files.size(low) < Files.size(high));

        Path big = dir.resolve("big.png");
        converter.convert(pdf, big, new ConversionOptions(300, PageRange.ALL, 0.9f, List.of()));
        assertTrue(javax.imageio.ImageIO.read(big.toFile()).getWidth() > 2400, "A4 @300dpi ≈ 2480px");
    }

    @Test
    void sameInputAndOutputIsRejected() throws Exception {
        Path txt = dir.resolve("a.txt");
        Files.writeString(txt, "hi");
        assertThrows(ConversionException.class, () -> converter.convert(txt, txt));
    }

    // ---- Batch ---------------------------------------------------------------

    private Path sampleTree() throws Exception {
        Path in = dir.resolve("in");
        Files.createDirectories(in.resolve("sub"));
        new DocxDocumentWriter().write(pages(1), in.resolve("a.docx"));
        Files.writeString(in.resolve("b.txt"), "# B\n\nbody");
        Files.writeString(in.resolve("ignored.xyz"), "not a document");
        new DocxDocumentWriter().write(pages(1), in.resolve("sub/c.docx"));
        return in;
    }

    @Test
    void plannerExpandsFoldersAndKeepsStructure() throws Exception {
        Path in = sampleTree();
        Path out = dir.resolve("out");
        JobPlanner planner = new JobPlanner(converter.registry());

        List<ConversionJob> flat = planner.plan(List.of(in), Format.PDF, out, false);
        assertEquals(List.of(out.resolve("a.pdf"), out.resolve("b.pdf")),
                flat.stream().map(ConversionJob::target).toList());

        List<ConversionJob> deep = planner.plan(List.of(in), Format.PDF, out, true);
        assertTrue(deep.stream().anyMatch(j -> j.target().equals(out.resolve("sub/c.pdf"))));
    }

    @Test
    void plannerRejectsCollidingOutputs() throws Exception {
        Path in = dir.resolve("clash");
        Files.createDirectories(in);
        Files.writeString(in.resolve("r.txt"), "x");
        new DocxDocumentWriter().write(pages(1), in.resolve("r.docx"));
        assertThrows(ConversionException.class,
                () -> new JobPlanner(converter.registry()).plan(List.of(in), Format.PDF, null, false));
    }

    @Test
    void batchContinuesPastFailuresAndRunsInParallel() throws Exception {
        Path in = sampleTree();
        Path broken = in.resolve("broken.docx");
        Files.writeString(broken, "this is not a zip");

        List<ConversionJob> jobs = new JobPlanner(converter.registry())
                .plan(List.of(in), Format.PDF, dir.resolve("out"), true);
        BatchResult result = new BatchConverter(converter, 3).run(jobs, ConversionOptions.DEFAULT, ConversionListener.NONE);

        assertEquals(4, result.total());
        assertEquals(1, result.failures().size());
        assertEquals(broken, result.failures().getFirst().job().source());
        assertTrue(Files.exists(dir.resolve("out/sub/c.pdf")));
    }

    // ---- CLI -----------------------------------------------------------------

    @Test
    void cliSingleAndBatchModes() throws Exception {
        Path in = sampleTree();
        Path txt = in.resolve("b.txt");

        assertEquals(0, Main.run(txt.toString(), dir.resolve("single.pdf").toString()));
        assertTrue(Files.exists(dir.resolve("single.pdf")));

        assertEquals(0, Main.run("--to", "docx", "-r", "-j", "2", "-o", dir.resolve("o").toString(), in.toString()));
        assertTrue(Files.exists(dir.resolve("o/b.docx")));
        assertTrue(Files.exists(dir.resolve("o/sub/c.docx")));

        assertEquals(2, Main.run("only-one-arg.pdf"));
        assertEquals(2, Main.run("--pages", "x-y", txt.toString(), "o.pdf"));
        assertEquals(1, Main.run(dir.resolve("missing.pdf").toString(), "o.png"));
    }
}

package com.docsmelt;

import com.docsmelt.cli.Main;
import com.docsmelt.io.ConversionException;
import com.docsmelt.io.PageRange;
import com.docsmelt.io.pdf.PdfDocumentReader;
import com.docsmelt.model.Document;
import com.docsmelt.model.Paragraph;
import com.docsmelt.ocr.OcrService;
import com.docsmelt.ocr.TesseractEngine;
import com.docsmelt.pdf.ImagesToPdf;
import com.docsmelt.pdf.PageEditPlan;
import com.docsmelt.pdf.PdfCompressor;
import com.docsmelt.pdf.PdfPageEditor;
import com.docsmelt.io.docx.DocxDocumentReader;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;

import static com.docsmelt.TestFiles.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class NewToolsTest {

    @TempDir
    Path dir;

    private static <T> T withPdf(Path pdf, Function<PDDocument, T> fn) {
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            return fn.apply(doc);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static List<String> pageTexts(Path pdf) {
        Document doc = new PdfDocumentReader().read(pdf);
        return doc.pages().stream()
                .map(p -> p.blocks().stream().filter(Paragraph.class::isInstance)
                        .map(b -> ((Paragraph) b).text()).findFirst().orElse(""))
                .toList();
    }

    // ---- Page editing --------------------------------------------------------

    @Test
    void planResolvesOrderDeleteAndRotation() {
        PageEditPlan plan = new PageEditPlan()
                .order(List.of(4, 1, 2, 3))
                .delete(PageRange.parse("2"))
                .rotate("90:1,4")
                .rotate("-90:4");
        assertEquals(List.of(new PageEditPlan.PageSpec(4, 0), new PageEditPlan.PageSpec(1, 90),
                new PageEditPlan.PageSpec(3, 0)), plan.resolve(4));

        assertThrows(ConversionException.class, () -> new PageEditPlan().order(List.of(9)).resolve(3));
        assertThrows(ConversionException.class, () -> new PageEditPlan().delete(PageRange.ALL).resolve(3));
        assertThrows(IllegalArgumentException.class, () -> new PageEditPlan().rotate("45"));
        assertThrows(IllegalArgumentException.class, () -> new PageEditPlan().order(List.of(1, 1)));
        assertThrows(IllegalArgumentException.class, () -> new PageEditPlan().rotatePerPage("1:90,1:180"));
    }

    @Test
    void editorRotatesDeletesAndReorders() {
        Path src = pdf(dir, "src.pdf", "S", 4);
        Path out = dir.resolve("out.pdf");
        PageEditPlan plan = new PageEditPlan().order(List.of(3, 1, 2, 4)).delete(PageRange.parse("4"))
                .rotatePerPage("1:90,3:270");

        assertEquals(3, new PdfPageEditor().apply(src, plan, out));
        assertEquals(List.of("S page 3", "S page 1", "S page 2"), pageTexts(out));
        assertEquals(List.of(270, 90, 0),
                withPdf(out, d -> { var r = new java.util.ArrayList<Integer>(); d.getPages().forEach(p -> r.add(p.getRotation())); return r; }));
    }

    // ---- Images to PDF -------------------------------------------------------

    @Test
    void imagesToPdfOnePagePerImageWithOrientationAndJpegPassthrough() throws Exception {
        Path wide = image(dir, "wide.png", photo(400, 200));
        Path tall = image(dir, "tall.jpg", photo(200, 400));
        Path out = dir.resolve("images.pdf");

        assertEquals(2, new ImagesToPdf().create(List.of(wide, tall), out, ImagesToPdf.PageSize.A4, 20));
        withPdf(out, d -> {
            PDPage first = d.getPage(0);
            PDPage second = d.getPage(1);
            assertTrue(first.getMediaBox().getWidth() > first.getMediaBox().getHeight(), "wide image → landscape");
            assertTrue(second.getMediaBox().getWidth() < second.getMediaBox().getHeight(), "tall image → portrait");
            try {
                COSName name = second.getResources().getXObjectNames().iterator().next();
                var img = (PDImageXObject) second.getResources().getXObject(name);
                assertEquals("jpg", img.getSuffix(), "JPEG embedded without re-encoding");
            } catch (Exception e) {
                throw new AssertionError(e);
            }
            return null;
        });

        Path fit = dir.resolve("fit.pdf");
        new ImagesToPdf().create(List.of(wide), fit, ImagesToPdf.PageSize.FIT, 0);
        assertEquals(300f, withPdf(fit, d -> d.getPage(0).getMediaBox().getWidth()), 0.5f, "400px at 96dpi = 300pt");

        Path notImage = pdf(dir, "x.pdf", "X", 1);
        assertThrows(ConversionException.class,
                () -> new ImagesToPdf().create(List.of(notImage), dir.resolve("bad.pdf"), ImagesToPdf.PageSize.A4, 0));
    }

    // ---- Compression ---------------------------------------------------------

    @Test
    void compressionShrinksImagesAndKeepsText() throws Exception {
        Path bloated = bloatedPdf(dir);
        Path small = dir.resolve("small.pdf");
        PdfCompressor.Result r = new PdfCompressor().compress(bloated, small, PdfCompressor.Level.HIGH);

        assertTrue(r.compressedBytes() < r.originalBytes() / 2, "expected >50% saving, got " + r);
        assertEquals(Files.size(small), r.compressedBytes());
        assertEquals(1, r.imagesRecompressed());
        assertTrue(new PdfDocumentReader().read(small).plainText().contains("Scanned report"));

        Path low = dir.resolve("low.pdf");
        long lowSize = new PdfCompressor().compress(bloated, low, PdfCompressor.Level.LOW).compressedBytes();
        assertTrue(lowSize > r.compressedBytes(), "low level keeps more quality than high");
    }

    @Test
    void compressionNeverGrowsAFile() throws Exception {
        Path textOnly = pdf(dir, "text.pdf", "T", 3);
        Path out = dir.resolve("out.pdf");
        PdfCompressor.Result r = new PdfCompressor().compress(textOnly, out, PdfCompressor.Level.HIGH);
        assertTrue(Files.size(out) <= Files.size(textOnly));
        assertEquals(0, r.imagesRecompressed());
    }

    // ---- OCR -----------------------------------------------------------------

    @Test
    void ocrRendersSelectedPdfPagesAndProducesEachOutputType() {
        FakeOcrEngine engine = new FakeOcrEngine(true);
        OcrService ocr = new OcrService(engine);
        Path scan = pdf(dir, "scan.pdf", "Scan", 5);
        OcrService.Options opts = new OcrService.Options("eng+swa", 200, PageRange.parse("2-4"), 0);

        ocr.recognize(scan, dir.resolve("out.txt"), opts);
        assertEquals(List.of(3), engine.imageCounts);
        assertEquals(200, engine.lastDpi);
        assertEquals("eng+swa", engine.lastLanguages);
        assertTrue(assertDoesNotThrow(() -> Files.readString(dir.resolve("out.txt"))).contains("Recognized text 3"));

        ocr.recognize(scan, dir.resolve("out.pdf"), opts);
        assertEquals(3, withPdf(dir.resolve("out.pdf"), PDDocument::getNumberOfPages));

        ocr.recognize(image(dir, "photo.png", photo(300, 200)), dir.resolve("out.docx"), OcrService.Options.defaults());
        assertTrue(new DocxDocumentReader().read(dir.resolve("out.docx")).plainText().contains("Recognized text 1"));
    }

    @Test
    void ocrValidatesLanguagesLimitsAndAvailability() {
        OcrService ocr = new OcrService(new FakeOcrEngine(true));
        Path scan = pdf(dir, "scan.pdf", "Scan", 5);
        Path out = dir.resolve("o.txt");

        assertThrows(IllegalArgumentException.class,
                () -> ocr.recognize(scan, out, new OcrService.Options("eng; rm -rf /", 300, PageRange.ALL, 0)));
        ConversionException missing = assertThrows(ConversionException.class,
                () -> ocr.recognize(scan, out, new OcrService.Options("fra", 300, PageRange.ALL, 0)));
        assertTrue(missing.getMessage().contains("eng, swa"), missing.getMessage());
        assertThrows(ConversionException.class,
                () -> ocr.recognize(scan, out, new OcrService.Options("eng", 300, PageRange.ALL, 3)));
        assertThrows(ConversionException.class,
                () -> ocr.recognize(scan, dir.resolve("o.png"), OcrService.Options.defaults()));

        ConversionException off = assertThrows(ConversionException.class,
                () -> new OcrService(new FakeOcrEngine(false)).recognize(scan, out, OcrService.Options.defaults()));
        assertTrue(off.getMessage().contains("not available"));
    }

    @Test
    void realTesseractReadsText() throws Exception {
        TesseractEngine engine = new TesseractEngine();
        assumeTrue(engine.isAvailable(), "Tesseract not installed; runs in the Docker build");

        Path png = image(dir, "hello.png", textImage("Hello DocSmelt 2026"));
        Path txt = dir.resolve("hello.txt");
        new OcrService(engine).recognize(png, txt, OcrService.Options.defaults());
        String text = Files.readString(txt);
        assertTrue(text.contains("Hello") && text.contains("2026"), text);

        Path searchable = dir.resolve("hello.pdf");
        new OcrService(engine).recognize(png, searchable, OcrService.Options.defaults());
        assertTrue(new PdfDocumentReader().read(searchable).plainText().contains("Hello"), "PDF has a text layer");
    }

    // ---- CLI -----------------------------------------------------------------

    @Test
    void cliCommands() {
        Path src = pdf(dir, "src.pdf", "S", 3);
        assertEquals(0, Main.run("pages", src.toString(), "--delete", "2", "--rotate", "90:1", "-o", dir.resolve("p.pdf").toString()));
        assertEquals(List.of("S page 1", "S page 3"), pageTexts(dir.resolve("p.pdf")));
        assertEquals(0, Main.run("pages", src.toString(), "--order", "3,1-2", "-o", dir.resolve("o.pdf").toString()));
        assertEquals(List.of("S page 3", "S page 1", "S page 2"), pageTexts(dir.resolve("o.pdf")));
        assertEquals(2, Main.run("pages", src.toString(), "-o", dir.resolve("x.pdf").toString()));
        assertEquals(2, Main.run("pages", src.toString(), "--rotate", "45", "-o", dir.resolve("x.pdf").toString()));

        Path img = image(dir, "a.png", photo(100, 100));
        assertEquals(0, Main.run("images", img.toString(), img.toString(), "-o", dir.resolve("i.pdf").toString()));
        assertEquals(2, withPdf(dir.resolve("i.pdf"), PDDocument::getNumberOfPages));
        assertEquals(2, Main.run("images", img.toString(), "--page-size", "a3", "-o", dir.resolve("i.pdf").toString()));

        assertEquals(0, Main.run("compress", bloatedPdf(dir).toString(), "-o", dir.resolve("c.pdf").toString(), "-l", "high"));
        assertEquals(2, Main.run("compress", src.toString(), "-o", dir.resolve("c.pdf").toString(), "-l", "max"));

        assertEquals(2, Main.run("ocr", src.toString())); // missing -o
    }
}

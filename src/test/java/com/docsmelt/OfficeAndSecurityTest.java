package com.docsmelt;

import com.docsmelt.cli.Main;
import com.docsmelt.core.Converter;
import com.docsmelt.io.ConversionException;
import com.docsmelt.io.PageRange;
import com.docsmelt.io.docx.DocxDocumentReader;
import com.docsmelt.io.docx.DocxDocumentWriter;
import com.docsmelt.io.office.PresentationReader;
import com.docsmelt.io.office.SpreadsheetReader;
import com.docsmelt.io.pdf.PdfDocumentReader;
import com.docsmelt.io.pdf.PdfDocumentWriter;
import com.docsmelt.model.Document;
import com.docsmelt.model.Paragraph;
import com.docsmelt.model.Table;
import com.docsmelt.pdf.PageNumberer;
import com.docsmelt.pdf.PdfSecurity;
import com.docsmelt.pdf.PdfSigner;
import com.docsmelt.pdf.PdfSplitter;
import com.docsmelt.pdf.SplitStrategy;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.sl.usermodel.Placeholder;
import org.apache.poi.xslf.usermodel.SlideLayout;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFSlideLayout;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.xslf.usermodel.XSLFTableRow;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.docsmelt.TestFiles.*;
import static org.junit.jupiter.api.Assertions.*;

class OfficeAndSecurityTest {

    @TempDir
    Path dir;

    private final Converter converter = new Converter();

    // ---- helpers ------------------------------------------------------------

    private Path workbook() throws Exception {
        Path file = dir.resolve("sales.xlsx");
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            XSSFSheet sheet = wb.createSheet("Sales");
            String[][] data = {{"Region", "Units", "Price"}, {"Arusha", "12", "1500"}, {"Dodoma", "7", "2000"}};
            for (int r = 0; r < data.length; r++) {
                var row = sheet.createRow(r);
                for (int c = 0; c < data[r].length; c++) {
                    if (r > 0 && c > 0) {
                        row.createCell(c).setCellValue(Double.parseDouble(data[r][c]));
                    } else {
                        row.createCell(c).setCellValue(data[r][c]);
                    }
                }
            }
            var total = sheet.createRow(3);
            total.createCell(0).setCellValue("Total");
            total.createCell(1).setCellFormula("SUM(B2:B3)");
            wb.getCreationHelper().createFormulaEvaluator().evaluateAll(); // cache the result like Excel does
            wb.createSheet("Hidden");
            wb.setSheetHidden(1, true);
            try (OutputStream out = Files.newOutputStream(file)) {
                wb.write(out);
            }
        }
        return file;
    }

    private Path presentation() throws Exception {
        Path file = dir.resolve("deck.pptx");
        try (XMLSlideShow show = new XMLSlideShow()) {
            XSLFSlideLayout layout = show.getSlideMasters().getFirst().getLayout(SlideLayout.TITLE_AND_CONTENT);
            XSLFSlide first = show.createSlide(layout);
            first.getPlaceholder(0).setText("Quarterly results");
            first.getPlaceholder(1).setText("Revenue grew 20%\nCosts fell");
            XSLFSlide second = show.createSlide();
            XSLFTable table = second.createTable();
            for (String[] values : new String[][]{{"Team", "Score"}, {"Simba", "3"}}) {
                XSLFTableRow row = table.addRow();
                for (String v : values) {
                    row.addCell().setText(v);
                }
            }
            table.setAnchor(new Rectangle2D.Double(50, 50, 300, 80));
            try (OutputStream out = Files.newOutputStream(file)) {
                show.write(out);
            }
        }
        return file;
    }

    private static String pdfText(Path pdf) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            return new PDFTextStripper().getText(doc);
        }
    }

    /** Renders page 1 as a viewer shows it (rotation applied). */
    private static BufferedImage render(Path pdf) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            return new PDFRenderer(doc).renderImageWithDPI(0, 50);
        }
    }

    /** Fraction of pixels in a region (fractions of the image) matching a test. */
    private static double share(BufferedImage img, double x0, double y0, double x1, double y1,
                                java.util.function.IntPredicate test) {
        int hits = 0, total = 0;
        for (int y = (int) (y0 * img.getHeight()); y < (int) (y1 * img.getHeight()); y++) {
            for (int x = (int) (x0 * img.getWidth()); x < (int) (x1 * img.getWidth()); x++) {
                total++;
                if (test.test(img.getRGB(x, y))) {
                    hits++;
                }
            }
        }
        return total == 0 ? 0 : (double) hits / total;
    }

    private static boolean dark(int rgb) {
        Color c = new Color(rgb);
        return c.getRed() + c.getGreen() + c.getBlue() < 300;
    }

    private static boolean red(int rgb) {
        Color c = new Color(rgb);
        return c.getRed() > 200 && c.getGreen() < 80 && c.getBlue() < 80;
    }

    private Path rotatedPdf(int rotation) throws Exception {
        Path pdf = pdf(dir, "r" + rotation + ".pdf", "R", 1);
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            doc.getPage(0).setRotation(rotation);
            Path out = dir.resolve("rot" + rotation + ".pdf");
            doc.save(out.toFile());
            return out;
        }
    }

    // ---- Excel ---------------------------------------------------------------

    @Test
    void excelReadsVisibleSheetsAsTablesWithCachedFormulas() throws Exception {
        Document doc = new SpreadsheetReader().read(workbook());
        assertEquals(1, doc.pages().size(), "hidden sheet skipped");
        assertEquals(Paragraph.heading("Sales"), doc.pages().getFirst().blocks().getFirst());
        Table t = (Table) doc.pages().getFirst().blocks().get(1);
        assertTrue(t.header());
        assertEquals(List.of("Region", "Units", "Price"), t.rows().getFirst());
        assertEquals(List.of("Total", "19", ""), t.rows().get(3), "formula shows its cached result");
    }

    @Test
    void legacyXlsIsReadToo() throws Exception {
        Path xls = dir.resolve("old.xls");
        try (HSSFWorkbook wb = new HSSFWorkbook(); OutputStream out = Files.newOutputStream(xls)) {
            var row = wb.createSheet("Legacy").createRow(0);
            row.createCell(0).setCellValue("Hello");
            row.createCell(1).setCellValue(42);
            wb.write(out);
        }
        Table t = (Table) new SpreadsheetReader().read(xls).pages().getFirst().blocks().get(1);
        assertEquals(List.of("Hello", "42"), t.rows().getFirst());
    }

    @Test
    void excelToPdfDrawsATableAndBack() throws Exception {
        Path pdf = dir.resolve("sales.pdf");
        converter.convert(workbook(), pdf);
        String text = pdfText(pdf);
        assertTrue(text.contains("Arusha") && text.contains("Dodoma") && text.contains("19"), text);

        // Word table → Excel keeps numbers as numbers
        Document word = new Document();
        word.newPage().add(Paragraph.heading("Budget"))
                .add(new Table(List.of(List.of("Item", "Cost"), List.of("Paper", "1,250"), List.of("Ink", "007")), true));
        Path docx = dir.resolve("budget.docx");
        new DocxDocumentWriter().write(word, docx);
        assertInstanceOf(Table.class, new DocxDocumentReader().read(docx).pages().getFirst().blocks().get(1),
                "Word tables are read as tables now");

        Path xlsx = dir.resolve("budget.xlsx");
        converter.convert(docx, xlsx);
        try (XSSFWorkbook wb = new XSSFWorkbook(xlsx.toFile())) {
            var sheet = wb.getSheet("Budget");
            assertNotNull(sheet, "sheet named after the heading");
            var paper = sheet.getRow(1).getCell(1);
            assertEquals(CellType.NUMERIC, paper.getCellType());
            assertEquals(1250.0, paper.getNumericCellValue());
            assertEquals(CellType.STRING, sheet.getRow(2).getCell(1).getCellType(), "leading-zero ids stay text");
        }
    }

    @Test
    void wideTablesGetLandscapePagesAndRepeatHeaders() throws Exception {
        List<List<String>> rows = new java.util.ArrayList<>();
        rows.add(List.of("A", "B", "C", "D", "E", "F", "G", "H"));
        for (int i = 0; i < 120; i++) {
            rows.add(List.of("r" + i, "x", "y", "z", "1", "2", "3", "4"));
        }
        Document doc = new Document();
        doc.newPage().add(new Table(rows, true));
        Path pdf = dir.resolve("wide.pdf");
        new PdfDocumentWriter().write(doc, pdf);
        try (PDDocument d = Loader.loadPDF(pdf.toFile())) {
            assertTrue(d.getNumberOfPages() > 1);
            var box = d.getPage(0).getMediaBox();
            assertTrue(box.getWidth() > box.getHeight(), "landscape for 8 columns");
            PDFTextStripper s = new PDFTextStripper();
            s.setStartPage(2);
            s.setEndPage(2);
            assertTrue(s.getText(d).strip().startsWith("A"), "header repeated on page 2");
        }
    }

    // ---- PowerPoint ----------------------------------------------------------

    @Test
    void powerpointReadsTitlesTextAndTables() throws Exception {
        Document doc = new PresentationReader().read(presentation());
        assertEquals(2, doc.pages().size());
        var first = doc.pages().getFirst().blocks();
        assertEquals(Paragraph.heading("Quarterly results"), first.getFirst());
        assertTrue(first.stream().anyMatch(b -> b instanceof Paragraph p && p.text().contains("Revenue grew 20%")));
        Table t = (Table) doc.pages().get(1).blocks().getFirst();
        assertEquals(List.of("Simba", "3"), t.rows().get(1));
    }

    @Test
    void powerpointToPdfKeepsSlideSizeAndLooks() throws Exception {
        Path pdf = dir.resolve("deck.pdf");
        converter.convert(presentation(), pdf);
        try (PDDocument d = Loader.loadPDF(pdf.toFile())) {
            assertEquals(2, d.getNumberOfPages());
            var box = d.getPage(0).getMediaBox();
            assertEquals(720f, box.getWidth(), 1f, "default POI slide is 720×540 pt");
            assertEquals(540f, box.getHeight(), 1f);
        }
        BufferedImage slide = render(pdf);
        assertTrue(share(slide, 0, 0, 1, 0.5, OfficeAndSecurityTest::dark) > 0.001, "the title was drawn");

        converter.convert(presentation(), dir.resolve("deck.docx"));
        assertTrue(new DocxDocumentReader().read(dir.resolve("deck.docx")).plainText().contains("Quarterly results"));
    }

    @Test
    void documentsAndPdfsBecomeSlides() throws Exception {
        Document doc = new Document();
        doc.newPage().add(Paragraph.heading("Plan")).add(Paragraph.body("Step one"))
                .add(new Table(List.of(List.of("K", "V"), List.of("a", "1")), true));
        Path docx = dir.resolve("plan.docx");
        new DocxDocumentWriter().write(doc, docx);
        Path pptx = dir.resolve("plan.pptx");
        converter.convert(docx, pptx);
        Document back = new PresentationReader().read(pptx);
        assertEquals(2, back.pages().size(), "text slide + table slide");
        assertEquals(Paragraph.heading("Plan"), back.pages().getFirst().blocks().getFirst());

        Path fromPdf = dir.resolve("pages.pptx");
        converter.convert(pdf(dir, "three.pdf", "T", 3), fromPdf);
        try (XMLSlideShow show = new XMLSlideShow(Files.newInputStream(fromPdf))) {
            assertEquals(3, show.getSlides().size(), "one picture slide per PDF page");
        }
    }

    // ---- Password ------------------------------------------------------------

    @Test
    void protectAndUnlock() throws Exception {
        Path src = pdf(dir, "secret.pdf", "Top secret", 2);
        Path locked = dir.resolve("locked.pdf");
        PdfSecurity security = new PdfSecurity();
        security.protect(src, locked, "s3cret!", null, new PdfSecurity.Permissions(false, false, true));

        assertThrows(InvalidPasswordException.class, () -> Loader.loadPDF(locked.toFile()).close());
        try (PDDocument d = Loader.loadPDF(locked.toFile(), "s3cret!")) {
            assertTrue(d.isEncrypted());
            assertEquals(256, d.getEncryption().getLength());
            assertFalse(d.getCurrentAccessPermission().canPrint());
            assertFalse(d.getCurrentAccessPermission().canExtractContent());
        }

        ConversionException wrong = assertThrows(ConversionException.class,
                () -> security.unlock(locked, dir.resolve("x.pdf"), "nope"));
        assertEquals("Wrong password", wrong.getMessage());
        assertThrows(ConversionException.class, () -> security.protect(locked, dir.resolve("y.pdf"), "abcd", null, PdfSecurity.Permissions.ALL));
        assertThrows(IllegalArgumentException.class, () -> security.protect(src, dir.resolve("y.pdf"), "abc", null, PdfSecurity.Permissions.ALL));

        Path open = dir.resolve("open.pdf");
        security.unlock(locked, open, "s3cret!");
        try (PDDocument d = Loader.loadPDF(open.toFile())) {
            assertFalse(d.isEncrypted());
        }
        assertTrue(pdfText(open).contains("Top secret page 2"));

        ConversionException friendly = assertThrows(ConversionException.class,
                () -> new PdfSplitter().split(locked, new SplitStrategy.EveryNPages(1), dir));
        assertTrue(friendly.getMessage().contains("password-protected"), friendly.getMessage());
        assertTrue(assertThrows(ConversionException.class, () -> new PdfDocumentReader().read(locked))
                .getMessage().contains("password-protected"));
    }

    // ---- Page numbers --------------------------------------------------------

    @Test
    void pageNumbersWithTemplateRangeAndStart() throws Exception {
        Path src = pdf(dir, "doc.pdf", "D", 4);
        Path out = dir.resolve("numbered.pdf");
        int n = new PageNumberer().apply(src, out, new PageNumberer.Options(PageNumberer.Position.BOTTOM_RIGHT,
                "Page {n} of {total}", 0, PageRange.parse("2-"), 10));
        assertEquals(3, n);
        String text = pdfText(out);
        assertTrue(text.contains("Page 1 of 3") && text.contains("Page 3 of 3"), text);
        assertFalse(text.contains("Page 0"), "cover page skipped");
        assertThrows(IllegalArgumentException.class,
                () -> new PageNumberer.Options(PageNumberer.Position.TOP_LEFT, "no placeholder", 1, PageRange.ALL, 10));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 90, 180, 270})
    void pageNumberIsAtTheBottomAsDisplayedOnRotatedPages(int rotation) throws Exception {
        Path out = dir.resolve("n" + rotation + ".pdf");
        new PageNumberer().apply(rotatedPdf(rotation), out, new PageNumberer.Options(PageNumberer.Position.BOTTOM_CENTER,
                "88{n}88", 1, PageRange.ALL, 24));
        BufferedImage page = render(out);
        double bottomCenter = share(page, 0.3, 0.88, 0.7, 1.0, OfficeAndSecurityTest::dark);
        double topBand = share(page, 0.3, 0.0, 0.7, 0.05, OfficeAndSecurityTest::dark);
        assertTrue(bottomCenter > 0.01, "number at bottom center for rotation " + rotation + ": " + bottomCenter);
        assertTrue(topBand < 0.001, "nothing at the top for rotation " + rotation);
    }

    // ---- Sign ----------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(ints = {0, 90, 180, 270})
    void signatureLandsWhereRequestedOnRotatedPages(int rotation) throws Exception {
        BufferedImage sig = new BufferedImage(200, 100, BufferedImage.TYPE_INT_ARGB);
        var g = sig.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, 200, 100);
        g.dispose();
        Path sigPng = dir.resolve("sig.png");
        ImageIO.write(sig, "png", sigPng.toFile());

        Path out = dir.resolve("s" + rotation + ".pdf");
        new PdfSigner().sign(rotatedPdf(rotation), sigPng, out, new PdfSigner.Placement(1, 0.6f, 0.75f, 0.3f));
        BufferedImage page = render(out);
        assertTrue(share(page, 0.62, 0.77, 0.88, 0.8, OfficeAndSecurityTest::red) > 0.9,
                "signature in the bottom-right area for rotation " + rotation);
        assertTrue(share(page, 0.0, 0.0, 0.5, 0.5, OfficeAndSecurityTest::red) < 0.001, "nowhere else");
    }

    @Test
    void signRejectsBadInput() throws Exception {
        Path src = pdf(dir, "c.pdf", "C", 2);
        Path sig = image(dir, "s.png", photo(50, 20));
        assertThrows(ConversionException.class,
                () -> new PdfSigner().sign(src, sig, dir.resolve("o.pdf"), new PdfSigner.Placement(5, 0.5f, 0.5f, 0.2f)));
        assertThrows(ConversionException.class,
                () -> new PdfSigner().sign(src, src, dir.resolve("o.pdf"), PdfSigner.Placement.defaults()));
        assertThrows(IllegalArgumentException.class, () -> new PdfSigner.Placement(1, 1.5f, 0.5f, 0.2f));
    }

    // ---- CLI -----------------------------------------------------------------

    @Test
    void cliCommands() throws Exception {
        Path src = pdf(dir, "cli.pdf", "C", 3);
        assertEquals(0, Main.run("protect", src.toString(), "-o", dir.resolve("p.pdf").toString(), "--password", "abcd1234", "--no-print"));
        assertEquals(1, Main.run("unlock", dir.resolve("p.pdf").toString(), "-o", dir.resolve("u.pdf").toString(), "--password", "wrong"));
        assertEquals(0, Main.run("unlock", dir.resolve("p.pdf").toString(), "-o", dir.resolve("u.pdf").toString(), "--password", "abcd1234"));
        assertEquals(0, Main.run("number", src.toString(), "-o", dir.resolve("n.pdf").toString(), "--format", "{n}/{total}"));
        assertTrue(pdfText(dir.resolve("n.pdf")).contains("2/3"));
        assertEquals(2, Main.run("number", src.toString(), "-o", dir.resolve("n.pdf").toString(), "--position", "middle"));
        Path sig = image(dir, "sig.png", photo(80, 30));
        assertEquals(0, Main.run("sign", src.toString(), "--signature", sig.toString(), "-o", dir.resolve("s.pdf").toString(), "--page", "2"));
        assertEquals(2, Main.run("sign", src.toString(), "--signature", sig.toString(), "-o", dir.resolve("s.pdf").toString(), "--x", "3"));
        assertEquals(0, Main.run(workbook().toString(), dir.resolve("w.pdf").toString()));
        assertEquals(0, Main.run(presentation().toString(), dir.resolve("deck.png").toString()));
        assertTrue(Files.exists(dir.resolve("deck-2.png")), "one image per slide");
    }
}

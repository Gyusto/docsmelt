package com.docsmelt;

import com.docsmelt.cli.Main;
import com.docsmelt.io.ConversionException;
import com.docsmelt.io.pdf.PdfDocumentWriter;
import com.docsmelt.model.Document;
import com.docsmelt.model.Paragraph;
import com.docsmelt.pdf.PdfAConverter;
import com.docsmelt.pdf.PdfAnnotator;
import com.docsmelt.pdf.PdfFormFiller;
import com.docsmelt.pdf.PdfRedactor;
import com.docsmelt.pdf.PdfSecurity;
import com.docsmelt.pdf.PdfTextFinder;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDComboBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.IntPredicate;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class EditingToolsTest {

    @TempDir
    Path dir;

    // ---- helpers -------------------------------------------------------------

    private Path contract(String name, int rotation) throws Exception {
        Document doc = new Document();
        doc.newPage().add(Paragraph.heading("Contract")).add(Paragraph.body("Tenant: John Doe, phone 0712345678. Rent is due monthly."));
        doc.newPage().add(Paragraph.body("Public schedule page"));
        Path pdf = dir.resolve(name);
        new PdfDocumentWriter().write(doc, pdf);
        try (PDDocument d = Loader.loadPDF(pdf.toFile())) {
            d.getPage(0).setRotation(rotation);
            d.getDocumentInformation().setAuthor("Secret Author");
            d.save(pdf.toFile());
        }
        return pdf;
    }

    private static String text(Path pdf, int page) throws Exception {
        try (PDDocument d = Loader.loadPDF(pdf.toFile())) {
            PDFTextStripper s = new PDFTextStripper();
            s.setStartPage(page);
            s.setEndPage(page);
            return s.getText(d);
        }
    }

    private static BufferedImage render(Path pdf, int page) throws Exception {
        try (PDDocument d = Loader.loadPDF(pdf.toFile())) {
            return new PDFRenderer(d).renderImageWithDPI(page - 1, 72);
        }
    }

    private static double share(BufferedImage img, double x0, double y0, double x1, double y1, IntPredicate test) {
        int hits = 0, total = 0;
        for (int y = (int) (y0 * img.getHeight()); y < (int) Math.ceil(y1 * img.getHeight()); y++) {
            for (int x = (int) (x0 * img.getWidth()); x < (int) Math.ceil(x1 * img.getWidth()); x++) {
                if (x < img.getWidth() && y < img.getHeight()) {
                    total++;
                    if (test.test(img.getRGB(x, y))) {
                        hits++;
                    }
                }
            }
        }
        return total == 0 ? 0 : (double) hits / total;
    }

    private static boolean dark(int rgb) {
        Color c = new Color(rgb);
        return c.getRed() + c.getGreen() + c.getBlue() < 200;
    }

    // ---- find & redact -------------------------------------------------------

    @ParameterizedTest
    @ValueSource(ints = {0, 90, 180, 270})
    void foundTextBoxesCoverTheWordsAsDisplayed(int rotation) throws Exception {
        Path pdf = contract("c" + rotation + ".pdf", rotation);
        List<PdfRedactor.Box> boxes = new PdfTextFinder().find(pdf, "JOHN DOE");
        assertEquals(1, boxes.size(), boxes.toString());
        PdfRedactor.Box b = boxes.getFirst();
        assertEquals(1, b.page());
        BufferedImage page = render(pdf, 1);
        assertTrue(share(page, b.x(), b.y(), b.x() + b.w(), b.y() + b.h(), EditingToolsTest::dark) > 0.05,
                "the box sits on the rendered words for rotation " + rotation + ": " + b);
    }

    @Test
    void redactionRemovesTheTextNotJustCoversIt() throws Exception {
        Path pdf = contract("contract.pdf", 0);
        List<PdfRedactor.Box> boxes = new java.util.ArrayList<>(new PdfTextFinder().find(pdf, "John Doe"));
        boxes.addAll(new PdfTextFinder().find(pdf, "0712345678"));
        Path out = dir.resolve("public.pdf");
        assertEquals(1, new PdfRedactor().redact(pdf, out, boxes, true));

        String page1 = text(out, 1);
        assertFalse(page1.contains("John") || page1.contains("0712345678"), "redacted text is gone: " + page1);
        assertTrue(text(out, 2).contains("Public schedule page"), "untouched pages keep their text");

        BufferedImage img = render(out, 1);
        PdfRedactor.Box b = boxes.getFirst();
        float ix = b.w() * 0.1f, iy = b.h() * 0.15f; // interior: edge pixels straddle the box at 72 dpi
        double black = share(img, b.x() + ix, b.y() + iy, b.x() + b.w() - ix, b.y() + b.h() - iy, EditingToolsTest::dark);
        assertTrue(black > 0.95, "solid black box, got " + black + " for " + b);
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            assertNull(d.getDocumentInformation().getAuthor(), "metadata removed");
            assertEquals(2, d.getNumberOfPages());
        }

        assertThrows(IllegalArgumentException.class, () -> PdfRedactor.Box.parse("1:0.5,0.5,0.8,0.1"));
        assertThrows(IllegalArgumentException.class, () -> new PdfTextFinder().find(pdf, "x"));
        assertThrows(ConversionException.class,
                () -> new PdfRedactor().redact(pdf, dir.resolve("x.pdf"), List.of(PdfRedactor.Box.parse("9:0,0,0.1,0.1")), false));
    }

    // ---- forms ---------------------------------------------------------------

    private Path form() throws Exception {
        Path file = dir.resolve("form.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            PDAcroForm form = new PDAcroForm(doc);
            doc.getDocumentCatalog().setAcroForm(form);
            PDResources resources = new PDResources();
            resources.put(COSName.getPDFName("Helv"), new PDType1Font(Standard14Fonts.FontName.HELVETICA));
            form.setDefaultResources(resources);
            form.setDefaultAppearance("/Helv 11 Tf 0 g");

            PDTextField name = new PDTextField(form);
            name.setPartialName("fullName");
            name.setAlternateFieldName("Full name");
            name.setRequired(true);
            add(form, page, name, new PDRectangle(60, 700, 250, 22));

            PDComboBox region = new PDComboBox(form);
            region.setPartialName("region");
            region.setOptions(List.of("Arusha", "Dodoma", "Mwanza"));
            add(form, page, region, new PDRectangle(60, 660, 150, 22));
            doc.save(file.toFile());
        }
        return file;
    }

    private static void add(PDAcroForm form, PDPage page, PDField field, PDRectangle rect) throws Exception {
        PDAnnotationWidget widget = field.getWidgets().getFirst();
        widget.setRectangle(rect);
        widget.setPage(page);
        page.getAnnotations().add(widget);
        form.getFields().add(field);
    }

    @Test
    void formFieldsAreListedAndFilled() throws Exception {
        Path form = form();
        List<PdfFormFiller.Field> fields = new PdfFormFiller().fields(form);
        assertEquals(List.of("fullName", "region"), fields.stream().map(PdfFormFiller.Field::name).toList());
        assertEquals("Full name", fields.getFirst().label());
        assertTrue(fields.getFirst().required());
        assertEquals(List.of("Arusha", "Dodoma", "Mwanza"), fields.get(1).options());

        Path filled = dir.resolve("filled.pdf");
        assertEquals(2, new PdfFormFiller().fill(form, filled, Map.of("fullName", "Asha Mwakyusa", "region", "Dodoma"), false));
        try (PDDocument d = Loader.loadPDF(filled.toFile())) {
            assertEquals("Asha Mwakyusa", d.getDocumentCatalog().getAcroForm().getField("fullName").getValueAsString());
        }

        Path flat = dir.resolve("flat.pdf");
        new PdfFormFiller().fill(form, flat, Map.of("fullName", "Asha Mwakyusa"), true);
        try (PDDocument d = Loader.loadPDF(flat.toFile())) {
            PDAcroForm acro = d.getDocumentCatalog().getAcroForm();
            assertTrue(acro == null || acro.getFields().isEmpty(), "no editable fields left");
        }
        assertTrue(text(flat, 1).contains("Asha Mwakyusa"), "the answer is printed on the page");

        assertThrows(ConversionException.class, () -> new PdfFormFiller().fill(form, dir.resolve("x.pdf"), Map.of("nope", "1"), false));
        assertThrows(ConversionException.class, () -> new PdfFormFiller().fields(contract("plain.pdf", 0)));
    }

    // ---- text & shapes -------------------------------------------------------

    @Test
    void textHighlightAndArrowAreDrawnWhereAskedEvenOnRotatedPages() throws Exception {
        Path pdf = contract("a.pdf", 90);
        Path out = dir.resolve("edited.pdf");
        List<PdfAnnotator.Mark> marks = List.of(
                PdfAnnotator.Mark.parse("text|1|0.05|0.05|28|#000000|Approved%20%E2%9C%93"),
                PdfAnnotator.Mark.parse("rect|1|0.5|0.5|0.3|0.1|#ffeb3b|fill|0.6"),
                PdfAnnotator.Mark.parse("line|2|0.1|0.9|0.6|0.7|#c73a2a|3|arrow"));
        assertEquals(3, new PdfAnnotator().apply(pdf, out, marks));

        assertTrue(text(out, 1).contains("Approved ✓"), text(out, 1));
        BufferedImage p1 = render(out, 1); // displayed sideways page: marks must still be where asked
        assertTrue(share(p1, 0.03, 0.03, 0.45, 0.12, EditingToolsTest::dark) > 0.03, "text at the top-left as displayed");
        assertTrue(share(p1, 0.52, 0.52, 0.78, 0.58, rgb -> {
            Color c = new Color(rgb);
            return c.getRed() > 230 && c.getGreen() > 200 && c.getBlue() < 140;
        }) > 0.9, "yellow highlight");
        BufferedImage p2 = render(out, 2);
        assertTrue(share(p2, 0.55, 0.68, 0.62, 0.74, rgb -> new Color(rgb).getRed() > 150 && new Color(rgb).getGreen() < 120) > 0.05,
                "arrowhead near the end point");

        assertThrows(IllegalArgumentException.class, () -> PdfAnnotator.Mark.parse("circle|1|0|0"));
        assertThrows(IllegalArgumentException.class, () -> PdfAnnotator.Mark.parse("rect|1|0.9|0.9|0.5|0.5|#000000|fill|1"));
    }

    // ---- PDF/A ---------------------------------------------------------------

    @Test
    void pdfaHasTheMarkersArchivesCheck() throws Exception {
        PdfAConverter converter = new PdfAConverter();
        assumeTrue(converter.isAvailable(), "Ghostscript not installed");
        Path pdf = contract("doc.pdf", 0);
        Path out = dir.resolve("archive.pdf");
        converter.convert(pdf, out);
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            String xmp = new String(d.getDocumentCatalog().getMetadata().toByteArray());
            assertTrue(xmp.matches("(?s).*pdfaid:part=?['\"]?>?2.*"), "PDF/A-2");
            assertEquals(1, d.getDocumentCatalog().getOutputIntents().size());
        }
        assertTrue(text(out, 1).contains("John Doe"), "text survives");

        Path locked = dir.resolve("locked.pdf");
        new PdfSecurity().protect(pdf, locked, "abcd", null, PdfSecurity.Permissions.ALL);
        assertTrue(assertThrows(ConversionException.class, () -> converter.convert(locked, dir.resolve("y.pdf")))
                .getMessage().contains("password-protected"));
    }

    // ---- CLI -----------------------------------------------------------------

    @Test
    void cliCommands() throws Exception {
        Path pdf = contract("cli.pdf", 0);
        assertEquals(0, Main.run("redact", pdf.toString(), "-o", dir.resolve("r.pdf").toString(), "--find", "John Doe"));
        assertFalse(text(dir.resolve("r.pdf"), 1).contains("John"));
        assertEquals(1, Main.run("redact", pdf.toString(), "-o", dir.resolve("r.pdf").toString(), "--find", "nowhere-to-be-found"));
        assertEquals(2, Main.run("redact", pdf.toString(), "-o", dir.resolve("r.pdf").toString(), "--box", "1:bad"));

        Path form = form();
        assertEquals(0, Main.run("form", form.toString()));
        assertEquals(0, Main.run("form", form.toString(), "-o", dir.resolve("f.pdf").toString(), "--set", "fullName=Juma", "--flatten"));
        assertTrue(text(dir.resolve("f.pdf"), 1).contains("Juma"));
        if (new PdfAConverter().isAvailable()) {
            assertEquals(0, Main.run("pdfa", pdf.toString(), "-o", dir.resolve("a.pdf").toString()));
        }
    }
}

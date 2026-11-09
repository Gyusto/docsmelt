package com.docsmelt;

import com.docsmelt.cli.Main;
import com.docsmelt.io.ConversionException;
import com.docsmelt.io.PageRange;
import com.docsmelt.io.docx.DocxDocumentWriter;
import com.docsmelt.io.image.ImageLoader;
import com.docsmelt.model.Document;
import com.docsmelt.model.Paragraph;
import com.docsmelt.pdf.ImagesToPdf;
import com.docsmelt.pdf.PdfImageExtractor;
import com.docsmelt.pdf.PdfPageSizer;
import com.docsmelt.pdf.PdfWatermarker;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.docsmelt.TestFiles.*;
import static org.junit.jupiter.api.Assertions.*;

class FinishingToolsTest {

    @TempDir
    Path dir;

    // ---- helpers -------------------------------------------------------------

    /** 200×100 image: red left half, blue right half. */
    private static BufferedImage halves() {
        BufferedImage img = new BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB);
        var g = img.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, 100, 100);
        g.setColor(Color.BLUE);
        g.fillRect(100, 0, 100, 100);
        g.dispose();
        return img;
    }

    /** A JPEG with an EXIF orientation tag, like a phone photo. */
    private Path phoneJpeg(String name, int orientation) throws Exception {
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        ImageIO.write(halves(), "jpeg", jpeg);
        byte[] plain = jpeg.toByteArray();
        byte[] tiff = {'M', 'M', 0, 42, 0, 0, 0, 8,              // big-endian TIFF header, IFD0 at 8
                0, 1,                                             // one entry
                0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, (byte) orientation, 0, 0, // Orientation, SHORT, 1 value
                0, 0, 0, 0};                                      // no next IFD
        byte[] exif = new byte[6 + tiff.length];
        System.arraycopy(new byte[]{'E', 'x', 'i', 'f', 0, 0}, 0, exif, 0, 6);
        System.arraycopy(tiff, 0, exif, 6, tiff.length);
        int len = exif.length + 2;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(plain, 0, 2);                                   // SOI
        out.write(new byte[]{(byte) 0xFF, (byte) 0xE1, (byte) (len >> 8), (byte) len});
        out.write(exif);
        out.write(plain, 2, plain.length - 2);
        Path file = dir.resolve(name);
        Files.write(file, out.toByteArray());
        return file;
    }

    private static boolean reddish(int rgb) {
        Color c = new Color(rgb);
        return c.getRed() > 180 && c.getBlue() < 90;
    }

    private static String text(Path pdf) throws Exception {
        try (PDDocument d = Loader.loadPDF(pdf.toFile())) {
            return new PDFTextStripper().getText(d);
        }
    }

    // ---- phone photos (EXIF orientation) -------------------------------------

    @Test
    void phonePhotosAreTurnedUpright() throws Exception {
        Path photo = phoneJpeg("phone.jpg", 6); // "rotate 90° clockwise to view"
        assertEquals(6, ImageLoader.orientation(photo));
        BufferedImage upright = ImageLoader.read(photo);
        assertEquals(100, upright.getWidth());
        assertEquals(200, upright.getHeight());
        assertTrue(reddish(upright.getRGB(50, 20)), "the red (left) half is now on top");
        assertFalse(reddish(upright.getRGB(50, 180)));

        Path pdf = dir.resolve("photo.pdf");
        new ImagesToPdf().create(List.of(photo), pdf, ImagesToPdf.PageSize.A4, 20);
        try (PDDocument d = Loader.loadPDF(pdf.toFile())) {
            PDRectangle box = d.getPage(0).getMediaBox();
            assertTrue(box.getHeight() > box.getWidth(), "a portrait photo gets a portrait page");
        }
        assertEquals(1, ImageLoader.orientation(image(dir, "plain.png", halves())), "no tag → upright");
    }

    /** The stored top-left corner must land where the EXIF spec says. */
    @ParameterizedTest
    @CsvSource({"1,TL", "2,TR", "3,BR", "4,BL", "5,TL", "6,TR", "7,BR", "8,BL"})
    void everyOrientationMapsTheCornerCorrectly(int orientation, String corner) {
        BufferedImage src = new BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB);
        src.setRGB(0, 0, Color.RED.getRGB());
        BufferedImage out = ImageLoader.applyOrientation(src, orientation);
        int x = corner.endsWith("L") ? 0 : out.getWidth() - 1;
        int y = corner.startsWith("T") ? 0 : out.getHeight() - 1;
        assertEquals(Color.RED.getRGB(), out.getRGB(x, y), "orientation " + orientation);
    }

    // ---- watermark -----------------------------------------------------------

    @Test
    void textWatermarkCentredAndTiled() throws Exception {
        Path src = pdf(dir, "doc.pdf", "Body", 2);
        Path centre = dir.resolve("centre.pdf");
        assertEquals(2, new PdfWatermarker().apply(src, centre, PdfWatermarker.Options.text("CONFIDENTIAL")));
        // Diagonal text can't be reliably read back by text extraction, so check the pixels instead.
        try (PDDocument d = Loader.loadPDF(centre.toFile())) {
            BufferedImage page = new PDFRenderer(d).renderImageWithDPI(0, 40);
            Color mid = new Color(page.getRGB(page.getWidth() / 2, page.getHeight() / 2));
            // Somewhere near the centre the semi-transparent red shows; it's never solid.
            boolean tinted = false;
            for (int dx = -40; dx <= 40 && !tinted; dx += 2) {
                Color c = new Color(page.getRGB(page.getWidth() / 2 + dx, page.getHeight() / 2));
                tinted = c.getRed() > 200 && c.getGreen() < 235 && c.getGreen() > 120;
            }
            assertTrue(tinted, "faint red near the centre, got " + mid);
        }

        Path tiled = dir.resolve("tiled.pdf");
        // Horizontal (angle 0) so text extraction can count the tiles; angles are checked by rendering above.
        var tile = new PdfWatermarker.Options("DRAFT", null, Color.GRAY, 0.2f, 0, 0,
                PdfWatermarker.Layout.TILE, PageRange.parse("2"));
        assertEquals(1, new PdfWatermarker().apply(src, tiled, tile));
        String t = text(tiled);
        assertTrue(t.split("DRAFT", -1).length - 1 >= 6, "tiled many times on page 2");

        assertThrows(IllegalArgumentException.class, () -> PdfWatermarker.Options.text(" "));
        assertThrows(IllegalArgumentException.class,
                () -> new PdfWatermarker.Options("x", null, Color.RED, 2f, 0, 0, PdfWatermarker.Layout.CENTER, PageRange.ALL));
    }

    @Test
    void imageWatermark() throws Exception {
        Path src = pdf(dir, "doc.pdf", "Body", 1);
        Path logo = image(dir, "logo.png", halves());
        Path out = dir.resolve("logo.pdf");
        new PdfWatermarker().apply(src, out, new PdfWatermarker.Options(null, logo, Color.BLACK, 0.5f, 0, 0.4f,
                PdfWatermarker.Layout.CENTER, PageRange.ALL));
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            BufferedImage page = new PDFRenderer(d).renderImageWithDPI(0, 40);
            Color c = new Color(page.getRGB(page.getWidth() / 2 - 10, page.getHeight() / 2));
            assertTrue(c.getRed() > 200 && c.getGreen() > 80 && c.getGreen() < 200, "half-transparent red logo, got " + c);
        }
    }

    // ---- extract images ------------------------------------------------------

    @Test
    void extractsJpegsByteForByteAndPngs() throws Exception {
        Path jpg = image(dir, "photo.jpg", photo(300, 200));
        Path png = image(dir, "chart.png", halves());
        Path pdf = dir.resolve("album.pdf");
        new ImagesToPdf().create(List.of(jpg, png), pdf, ImagesToPdf.PageSize.A4, 20);

        List<Path> out = new PdfImageExtractor().extract(pdf, dir.resolve("out"), PageRange.ALL);
        assertEquals(List.of("album-page1-1.jpg", "album-page2-1.png"), out.stream().map(p -> p.getFileName().toString()).toList());
        assertArrayEquals(Files.readAllBytes(jpg), Files.readAllBytes(out.getFirst()), "JPEG copied without re-encoding");
        assertEquals(200, ImageIO.read(out.get(1).toFile()).getWidth());

        assertThrows(ConversionException.class,
                () -> new PdfImageExtractor().extract(pdf(dir, "text.pdf", "T", 1), dir.resolve("none"), PageRange.ALL));
    }

    // ---- crop & resize -------------------------------------------------------

    @Test
    void autoCropTrimsWhiteMarginsAndKeepsText() throws Exception {
        Path src = pdf(dir, "doc.pdf", "Short line", 1);
        Path out = dir.resolve("cropped.pdf");
        assertEquals(1, new PdfPageSizer().crop(src, out, -1));
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            PDRectangle box = d.getPage(0).getCropBox();
            assertTrue(box.getWidth() < 300 && box.getHeight() < 100, "trimmed to the text, got " + box);
        }
        assertTrue(text(out).contains("Short line page 1"));

        Path fixed = dir.resolve("fixed.pdf");
        new PdfPageSizer().crop(src, fixed, 10);
        try (PDDocument d = Loader.loadPDF(fixed.toFile())) {
            assertEquals(PDRectangle.A4.getWidth() - 2 * 10 * 72 / 25.4f, d.getPage(0).getCropBox().getWidth(), 0.5f);
        }
    }

    @Test
    void resizeFitsPagesAndKeepsOrientation() throws Exception {
        Document doc = new Document();
        doc.newPage().add(Paragraph.body("Portrait"));
        Path src = dir.resolve("letter.pdf");
        new com.docsmelt.io.pdf.PdfDocumentWriter().write(doc, src);
        try (PDDocument d = Loader.loadPDF(src.toFile())) { // make page 1 landscape Letter
            d.getPage(0).setMediaBox(new PDRectangle(PDRectangle.LETTER.getHeight(), PDRectangle.LETTER.getWidth()));
            d.save(dir.resolve("land.pdf").toFile());
        }
        Path out = dir.resolve("a4.pdf");
        new PdfPageSizer().resize(dir.resolve("land.pdf"), out, PdfPageSizer.Paper.A4);
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            PDRectangle box = d.getPage(0).getMediaBox();
            assertEquals(PDRectangle.A4.getHeight(), box.getWidth(), 0.5f, "landscape stays landscape");
            assertEquals(PDRectangle.A4.getWidth(), box.getHeight(), 0.5f);
        }
        assertTrue(text(out).contains("Portrait"));
    }

    // ---- CLI -----------------------------------------------------------------

    @Test
    void cliCommands() throws Exception {
        Path src = pdf(dir, "cli.pdf", "C", 2);
        assertEquals(0, Main.run("watermark", src.toString(), "-o", dir.resolve("w.pdf").toString(), "--text", "DRAFT", "--layout", "tile"));
        assertEquals(2, Main.run("watermark", src.toString(), "-o", dir.resolve("w.pdf").toString()));
        assertEquals(2, Main.run("watermark", src.toString(), "-o", dir.resolve("w.pdf").toString(), "--text", "x", "--color", "red"));
        assertEquals(0, Main.run("crop", src.toString(), "-o", dir.resolve("c.pdf").toString()));
        assertEquals(0, Main.run("crop", src.toString(), "-o", dir.resolve("r.pdf").toString(), "--resize", "letter"));
        assertEquals(2, Main.run("crop", src.toString(), "-o", dir.resolve("r.pdf").toString(), "--resize", "b5"));
        Path album = dir.resolve("album.pdf");
        new ImagesToPdf().create(List.of(image(dir, "a.png", halves())), album, ImagesToPdf.PageSize.FIT, 0);
        assertEquals(0, Main.run("extract-images", album.toString(), "-o", dir.resolve("imgs").toString()));
        assertTrue(Files.exists(dir.resolve("imgs/album-page1-1.png")));
        assertEquals(1, Main.run("extract-images", src.toString(), "-o", dir.resolve("none").toString()));
        new DocxDocumentWriter().write(new Document(), dir.resolve("unused.docx"));
    }
}

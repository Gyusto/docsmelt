package com.docsmelt;

import com.docsmelt.io.pdf.PdfDocumentWriter;
import com.docsmelt.model.Document;
import com.docsmelt.model.ImageBlock;
import com.docsmelt.model.Paragraph;
import com.docsmelt.ocr.OcrEngine;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;

/** Shared fixtures for the tests. */
final class TestFiles {

    private TestFiles() {
    }

    /** A PDF whose page N contains the text "<prefix> page N". */
    static Path pdf(Path dir, String name, String prefix, int pages) {
        Document doc = new Document();
        for (int i = 1; i <= pages; i++) {
            doc.newPage().add(Paragraph.body(prefix + " page " + i));
        }
        Path out = dir.resolve(name);
        new PdfDocumentWriter().write(doc, out);
        return out;
    }

    /** Noisy photo-like image: compresses badly losslessly, well as JPEG. */
    static BufferedImage photo(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Random rnd = new Random(42);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int base = (x * 255 / w) << 16 | (y * 255 / h) << 8 | 128;
                img.setRGB(x, y, base ^ (rnd.nextInt(32) * 0x010101));
            }
        }
        return img;
    }

    static Path image(Path dir, String name, BufferedImage img) {
        Path out = dir.resolve(name);
        String ext = name.substring(name.lastIndexOf('.') + 1);
        try {
            ImageIO.write(img, ext.equals("jpg") ? "jpeg" : ext, out.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    /** A PDF holding one big lossless photo, i.e. a typical bloated scan. */
    static Path bloatedPdf(Path dir) {
        Document doc = new Document();
        doc.newPage().add(Paragraph.body("Scanned report")).add(new ImageBlock(photo(1400, 1000)));
        Path out = dir.resolve("bloated.pdf");
        new PdfDocumentWriter().write(doc, out);
        return out;
    }

    /** Black text on white, large enough for real OCR. */
    static BufferedImage textImage(String text) {
        BufferedImage img = new BufferedImage(1400, 300, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        g.setColor(Color.BLACK);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 72));
        g.drawString(text, 60, 180);
        g.dispose();
        return img;
    }

    /**
     * Stand-in for Tesseract: "recognizes" page N as "Recognized text N" and
     * remembers what it was asked to do.
     */
    static class FakeOcrEngine implements OcrEngine {
        final List<Integer> imageCounts = new ArrayList<>();
        int lastDpi;
        int pagesSeen;
        String lastLanguages;
        private final boolean available;

        FakeOcrEngine(boolean available) {
            this.available = available;
        }

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public List<String> languages() {
            return available ? List.of("eng", "swa") : List.of();
        }

        @Override
        public void recognize(List<Path> images, String languages, int dpi, Path outputBase, Set<Output> outputs) {
            imageCounts.add(images.size());
            lastDpi = dpi;
            lastLanguages = languages;
            for (Path p : images) {
                if (!Files.exists(p)) {
                    throw new AssertionError("missing page image " + p);
                }
            }
            List<String> pages = new ArrayList<>();
            for (int i = 1; i <= images.size(); i++) {
                pages.add("Recognized text " + (++pagesSeen)); // keeps counting across one-page calls
            }
            try {
                if (outputs.contains(Output.TEXT)) {
                    Files.writeString(outputBase.resolveSibling(outputBase.getFileName() + ".txt"),
                            String.join("\f", pages));
                }
                if (outputs.contains(Output.SEARCHABLE_PDF)) {
                    Document doc = new Document();
                    pages.forEach(t -> doc.newPage().add(Paragraph.body(t)));
                    new PdfDocumentWriter().write(doc, outputBase.resolveSibling(outputBase.getFileName() + ".pdf"));
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}

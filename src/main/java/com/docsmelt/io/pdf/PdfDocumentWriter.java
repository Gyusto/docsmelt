package com.docsmelt.io.pdf;

import com.docsmelt.io.ConversionException;
import com.docsmelt.io.DocumentWriter;
import com.docsmelt.io.WriteOptions;
import com.docsmelt.model.Block;
import com.docsmelt.model.Document;
import com.docsmelt.model.ImageBlock;
import com.docsmelt.model.Page;
import com.docsmelt.model.Paragraph;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Lays out paragraphs and images onto A4 pages with Apache PDFBox. Text uses
 * embedded Unicode fonts (bundled DejaVu Sans plus optional fallbacks).
 */
public final class PdfDocumentWriter implements DocumentWriter {

    private static final PDRectangle PAGE_SIZE = PDRectangle.A4;
    private static final float MARGIN = 56f;
    private static final float BODY_SIZE = 11f;
    private static final float HEADING_SIZE = 16f;
    private static final float LEADING = 1.4f;
    private static final float PARAGRAPH_GAP = 8f;

    @Override
    public List<Path> write(Document document, Path target, WriteOptions options) {
        try (PDDocument pdf = new PDDocument()) {
            try (Layout layout = new Layout(pdf, options)) {
                for (Page page : document.pages()) {
                    layout.startPage();
                    for (Block block : page.blocks()) {
                        switch (block) {
                            case Paragraph p -> layout.paragraph(p);
                            case ImageBlock img -> layout.image(img);
                        }
                    }
                }
                if (document.pages().isEmpty()) {
                    layout.startPage();
                }
            }
            pdf.save(target.toFile());
            return List.of(target);
        } catch (IOException e) {
            throw new ConversionException("Could not write PDF " + target + ": " + e.getMessage(), e);
        }
    }

    /** Keeps track of the current page and vertical cursor while laying out content. */
    private static final class Layout implements AutoCloseable {

        private final PDDocument pdf;
        private final FontChain regular;
        private final FontChain bold;
        private final float contentWidth = PAGE_SIZE.getWidth() - 2 * MARGIN;
        private final float contentHeight = PAGE_SIZE.getHeight() - 2 * MARGIN;

        private PDPageContentStream stream;
        private float y;

        Layout(PDDocument pdf, WriteOptions options) {
            this.pdf = pdf;
            this.regular = FontChain.regular(pdf, options.extraFonts());
            this.bold = FontChain.bold(pdf, options.extraFonts());
        }

        void startPage() throws IOException {
            closeStream();
            PDPage page = new PDPage(PAGE_SIZE);
            pdf.addPage(page);
            stream = new PDPageContentStream(pdf, page);
            y = PAGE_SIZE.getHeight() - MARGIN;
        }

        void paragraph(Paragraph p) throws IOException {
            FontChain fonts = p.isHeading() ? bold : regular;
            float size = p.isHeading() ? HEADING_SIZE : BODY_SIZE;
            float lineHeight = size * LEADING;

            String text = p.text().replaceAll("[\\s\\p{Cntrl}]+", " ").strip();
            for (String line : wrap(text, fonts, size)) {
                if (y - lineHeight < MARGIN) {
                    startPage();
                }
                y -= lineHeight;
                stream.beginText();
                stream.newLineAtOffset(MARGIN, y);
                for (FontChain.Run run : fonts.runs(line)) {
                    stream.setFont(run.font(), size);
                    stream.showText(run.text());
                }
                stream.endText();
            }
            y -= PARAGRAPH_GAP;
        }

        void image(ImageBlock block) throws IOException {
            // Treat 1 pixel as 1 point, shrinking to fit the printable area.
            float scale = Math.min(1f, Math.min(contentWidth / block.width(), contentHeight / block.height()));
            float w = block.width() * scale;
            float h = block.height() * scale;
            if (y - h < MARGIN) {
                startPage();
            }
            y -= h;
            PDImageXObject xObject = LosslessFactory.createFromImage(pdf, block.image());
            stream.drawImage(xObject, MARGIN + (contentWidth - w) / 2, y, w, h);
            y -= PARAGRAPH_GAP;
        }

        /**
         * Greedy word wrap. Words wider than a line (or scripts without spaces,
         * like Chinese) are broken between characters.
         */
        private List<String> wrap(String text, FontChain fonts, float size) throws IOException {
            List<String> lines = new ArrayList<>();
            StringBuilder line = new StringBuilder();
            for (String word : text.split(" ")) {
                String candidate = line.isEmpty() ? word : line + " " + word;
                if (fonts.width(candidate, size) <= contentWidth) {
                    line.setLength(0);
                    line.append(candidate);
                    continue;
                }
                if (!line.isEmpty()) {
                    lines.add(line.toString());
                    line.setLength(0);
                }
                for (int i = 0; i < word.length(); ) {
                    int cp = word.codePointAt(i);
                    i += Character.charCount(cp);
                    String next = line + new String(Character.toChars(cp));
                    if (!line.isEmpty() && fonts.width(next, size) > contentWidth) {
                        lines.add(line.toString());
                        line.setLength(0);
                    }
                    line.appendCodePoint(cp);
                }
            }
            if (!line.isEmpty()) {
                lines.add(line.toString());
            }
            return lines;
        }

        private void closeStream() throws IOException {
            if (stream != null) {
                stream.close();
                stream = null;
            }
        }

        @Override
        public void close() throws IOException {
            closeStream();
        }
    }
}

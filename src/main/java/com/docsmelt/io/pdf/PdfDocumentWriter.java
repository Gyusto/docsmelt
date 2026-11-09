package com.docsmelt.io.pdf;

import com.docsmelt.io.ConversionException;
import com.docsmelt.io.DocumentWriter;
import com.docsmelt.io.TableLayout;
import com.docsmelt.io.WriteOptions;
import com.docsmelt.model.Block;
import com.docsmelt.model.Document;
import com.docsmelt.model.ImageBlock;
import com.docsmelt.model.Page;
import com.docsmelt.model.Paragraph;
import com.docsmelt.model.Table;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Lays out paragraphs, tables and images onto A4 pages with Apache PDFBox.
 * Text uses embedded Unicode fonts (bundled DejaVu Sans plus optional
 * fallbacks). Pages holding a wide table are turned to landscape, and
 * fixed-layout pages (slides) are drawn from their snapshot at their own size.
 */
public final class PdfDocumentWriter implements DocumentWriter {

    private static final PDRectangle A4 = PDRectangle.A4;
    private static final PDRectangle A4_LANDSCAPE = new PDRectangle(A4.getHeight(), A4.getWidth());
    private static final float MARGIN = 56f;
    private static final float BODY_SIZE = 11f;
    private static final float HEADING_SIZE = 16f;
    private static final float LEADING = 1.4f;
    private static final float PARAGRAPH_GAP = 8f;
    private static final float TABLE_SIZE = 9f;
    private static final float TABLE_MIN_SIZE = 6.5f;
    private static final float CELL_PAD = 3f;
    private static final int LANDSCAPE_COLUMNS = 7;
    private static final Color GRID = new Color(0xC9D3E0);
    private static final Color HEADER_FILL = new Color(0xEEF3FB);

    /** Slides are drawn from their rendered snapshot, so ask readers for one. */
    @Override
    public boolean wantsSnapshots() {
        return true;
    }

    @Override
    public List<Path> write(Document document, Path target, WriteOptions options) {
        try (PDDocument pdf = new PDDocument()) {
            try (Layout layout = new Layout(pdf, options)) {
                for (Page page : document.pages()) {
                    if (page.fixedSize().isPresent() && page.snapshot().isPresent()) {
                        layout.fixedPage(page.fixedSize().get(), page.snapshot().get());
                        continue;
                    }
                    layout.startPage(needsLandscape(page));
                    for (Block block : page.blocks()) {
                        switch (block) {
                            case Paragraph p -> layout.paragraph(p);
                            case ImageBlock img -> layout.image(img);
                            case Table t -> layout.table(t);
                        }
                    }
                }
                if (document.pages().isEmpty()) {
                    layout.startPage(false);
                }
            }
            pdf.save(target.toFile());
            return List.of(target);
        } catch (IOException e) {
            throw new ConversionException("Could not write PDF " + target + ": " + e.getMessage(), e);
        }
    }

    private static boolean needsLandscape(Page page) {
        return page.blocks().stream()
                .anyMatch(b -> b instanceof Table t && t.columnCount() >= LANDSCAPE_COLUMNS);
    }

    /** Keeps track of the current page and vertical cursor while laying out content. */
    private static final class Layout implements AutoCloseable {

        private final PDDocument pdf;
        private final FontChain regular;
        private final FontChain bold;

        private PDPageContentStream stream;
        private PDRectangle size = A4;
        private float y;

        Layout(PDDocument pdf, WriteOptions options) {
            this.pdf = pdf;
            this.regular = FontChain.regular(pdf, options.extraFonts());
            this.bold = FontChain.bold(pdf, options.extraFonts());
        }

        private float contentWidth() {
            return size.getWidth() - 2 * MARGIN;
        }

        private float contentHeight() {
            return size.getHeight() - 2 * MARGIN;
        }

        void startPage(boolean landscape) throws IOException {
            closeStream();
            size = landscape ? A4_LANDSCAPE : A4;
            PDPage page = new PDPage(size);
            pdf.addPage(page);
            stream = new PDPageContentStream(pdf, page);
            y = size.getHeight() - MARGIN;
        }

        /** Continue on a fresh page with the same orientation. */
        private void nextPage() throws IOException {
            startPage(size == A4_LANDSCAPE);
        }

        void fixedPage(Page.Size pageSize, BufferedImage snapshot) throws IOException {
            closeStream();
            PDPage page = new PDPage(new PDRectangle(pageSize.widthPt(), pageSize.heightPt()));
            pdf.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(pdf, page)) {
                content.drawImage(LosslessFactory.createFromImage(pdf, snapshot), 0, 0,
                        pageSize.widthPt(), pageSize.heightPt());
            }
        }

        void paragraph(Paragraph p) throws IOException {
            FontChain fonts = p.isHeading() ? bold : regular;
            float fontSize = p.isHeading() ? HEADING_SIZE : BODY_SIZE;
            float lineHeight = fontSize * LEADING;
            for (String line : wrap(clean(p.text()), fonts, fontSize, contentWidth())) {
                if (y - lineHeight < MARGIN) {
                    nextPage();
                }
                y -= lineHeight;
                text(fonts, fontSize, MARGIN, y, line);
            }
            y -= PARAGRAPH_GAP;
        }

        void image(ImageBlock block) throws IOException {
            // Treat 1 pixel as 1 point, shrinking to fit the printable area.
            float scale = Math.min(1f, Math.min(contentWidth() / block.width(), contentHeight() / block.height()));
            float w = block.width() * scale;
            float h = block.height() * scale;
            if (y - h < MARGIN) {
                nextPage();
            }
            y -= h;
            PDImageXObject xObject = LosslessFactory.createFromImage(pdf, block.image());
            stream.drawImage(xObject, MARGIN + (contentWidth() - w) / 2, y, w, h);
            y -= PARAGRAPH_GAP;
        }

        /** Grid with a shaded header row (repeated on every page) and wrapped cell text. */
        void table(Table table) throws IOException {
            if (table.isEmpty()) {
                return;
            }
            float fontSize = TABLE_SIZE;
            float[] widths = widths(table, fontSize);
            // Shrink the font a little before letting words break mid-way.
            while (fontSize > TABLE_MIN_SIZE && sum(widths) >= contentWidth() - 0.5f && wordsDontFit(table, widths, fontSize)) {
                fontSize -= 0.5f;
                widths = widths(table, fontSize);
            }
            float lineHeight = fontSize * 1.25f;
            for (int r = 0; r < table.rowCount(); r++) {
                boolean isHeader = table.header() && r == 0;
                List<List<String>> cells = wrapRow(table, r, widths, isHeader ? bold : regular, fontSize);
                float rowHeight = rowHeight(cells, lineHeight);
                if (y - rowHeight < MARGIN && y < size.getHeight() - MARGIN - 1) {
                    nextPage();
                    if (table.header() && r > 0) { // repeat the header on the new page
                        List<List<String>> head = wrapRow(table, 0, widths, bold, fontSize);
                        drawRow(head, widths, rowHeight(head, lineHeight), lineHeight, fontSize, true);
                    }
                }
                drawRow(cells, widths, Math.min(rowHeight, contentHeight()), lineHeight, fontSize, isHeader);
            }
            y -= PARAGRAPH_GAP * 1.5f;
        }

        private float[] widths(Table table, float fontSize) {
            // Measure in bold: it's the wider face, so header cells never break mid-word.
            return TableLayout.columnWidths(table, s -> {
                try {
                    return bold.width(clean(s), fontSize);
                } catch (IOException e) {
                    return s.length() * fontSize * 0.5;
                }
            }, contentWidth(), CELL_PAD);
        }

        private boolean wordsDontFit(Table table, float[] widths, float fontSize) throws IOException {
            int rows = Math.min(table.rowCount(), 200);
            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < widths.length; c++) {
                    for (String word : table.cell(r, c).split("\\s+")) {
                        if (regular.width(clean(word), fontSize) > widths[c] - 2 * CELL_PAD) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        private List<List<String>> wrapRow(Table table, int r, float[] widths, FontChain fonts, float fontSize)
                throws IOException {
            List<List<String>> cells = new ArrayList<>();
            for (int c = 0; c < widths.length; c++) {
                cells.add(wrap(clean(table.cell(r, c)), fonts, fontSize, widths[c] - 2 * CELL_PAD));
            }
            return cells;
        }

        private static float rowHeight(List<List<String>> cells, float lineHeight) {
            int lines = cells.stream().mapToInt(List::size).max().orElse(1);
            return Math.max(1, lines) * lineHeight + 2 * CELL_PAD;
        }

        private void drawRow(List<List<String>> cells, float[] widths, float rowHeight, float lineHeight,
                             float fontSize, boolean header) throws IOException {
            float top = y;
            float bottom = y - rowHeight;
            float x = MARGIN;
            if (header) {
                stream.setNonStrokingColor(HEADER_FILL);
                stream.addRect(MARGIN, bottom, sum(widths), rowHeight);
                stream.fill();
            }
            stream.setNonStrokingColor(Color.BLACK);
            for (int c = 0; c < widths.length; c++) {
                float lineY = top - CELL_PAD - fontSize;
                for (String line : cells.get(c)) {
                    if (lineY < bottom + CELL_PAD - 1) {
                        break; // a single row taller than a page is clipped
                    }
                    text(header ? bold : regular, fontSize, x + CELL_PAD, lineY, line);
                    lineY -= lineHeight;
                }
                x += widths[c];
            }
            stream.setStrokingColor(GRID);
            stream.setLineWidth(0.5f);
            x = MARGIN;
            for (float w : widths) {
                stream.addRect(x, bottom, w, rowHeight);
                x += w;
            }
            stream.stroke();
            y = bottom;
        }

        private void text(FontChain fonts, float fontSize, float x, float baseline, String line) throws IOException {
            if (line.isEmpty()) {
                return;
            }
            stream.beginText();
            stream.newLineAtOffset(x, baseline);
            for (FontChain.Run run : fonts.runs(line)) {
                stream.setFont(run.font(), fontSize);
                stream.showText(run.text());
            }
            stream.endText();
        }

        private static float sum(float[] values) {
            float s = 0;
            for (float v : values) {
                s += v;
            }
            return s;
        }

        private static String clean(String text) {
            return text.replaceAll("[\\s\\p{Cntrl}]+", " ").strip();
        }

        /**
         * Greedy word wrap. Words wider than a line (or scripts without spaces,
         * like Chinese) are broken between characters.
         */
        private static List<String> wrap(String text, FontChain fonts, float fontSize, float maxWidth) throws IOException {
            List<String> lines = new ArrayList<>();
            StringBuilder line = new StringBuilder();
            for (String word : text.split(" ")) {
                String candidate = line.isEmpty() ? word : line + " " + word;
                if (fonts.width(candidate, fontSize) <= maxWidth) {
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
                    if (!line.isEmpty() && fonts.width(next, fontSize) > maxWidth) {
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

package com.docsmelt.io.image;

import com.docsmelt.model.Block;
import com.docsmelt.model.ImageBlock;
import com.docsmelt.model.Page;
import com.docsmelt.model.Paragraph;
import com.docsmelt.model.Table;
import com.docsmelt.io.TableLayout;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Draws a page's blocks onto A4-sized images (150 dpi) when no snapshot of the
 * original is available — e.g. for Word → PNG. Content that overflows continues
 * on additional images.
 */
public final class PageRasterizer {

    /** All sizes below are tuned for 150 dpi and scaled for other resolutions. */
    private static final int BASE_DPI = 150;

    private final int width;
    private final int height;
    private final int margin;
    private final int paragraphGap;
    private final Font body;
    private final Font heading;

    public PageRasterizer() {
        this(BASE_DPI);
    }

    public PageRasterizer(int dpi) {
        double s = (double) dpi / BASE_DPI;
        this.width = (int) Math.round(1240 * s);   // A4 = 8.27in wide
        this.height = (int) Math.round(1754 * s);  // A4 = 11.69in tall
        this.margin = (int) Math.round(120 * s);
        this.paragraphGap = (int) Math.round(18 * s);
        this.body = new Font(Font.SANS_SERIF, Font.PLAIN, (int) Math.round(23 * s));
        this.heading = new Font(Font.SANS_SERIF, Font.BOLD, (int) Math.round(34 * s));
    }

    public List<BufferedImage> render(Page page) {
        Canvas canvas = new Canvas();
        for (Block block : page.blocks()) {
            switch (block) {
                case Paragraph p -> canvas.paragraph(p);
                case ImageBlock img -> canvas.image(img);
                case Table t -> canvas.table(t);
            }
        }
        return canvas.finish();
    }

    private final class Canvas {
        private final List<BufferedImage> images = new ArrayList<>();
        private final int contentWidth = width - 2 * margin;
        private final int contentHeight = height - 2 * margin;
        private Graphics2D g;
        private int y;

        Canvas() {
            newSheet();
        }

        private void newSheet() {
            if (g != null) {
                g.dispose();
            }
            BufferedImage sheet = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            images.add(sheet);
            g = sheet.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, width, height);
            g.setColor(Color.BLACK);
            y = margin;
        }

        void paragraph(Paragraph p) {
            g.setFont(p.isHeading() ? heading : body);
            FontMetrics fm = g.getFontMetrics();
            int lineHeight = (int) (fm.getHeight() * 1.25);
            for (String line : wrap(p.text(), fm)) {
                if (y + lineHeight > height - margin) {
                    newSheet();
                    g.setFont(p.isHeading() ? heading : body);
                }
                g.drawString(line, margin, y + fm.getAscent());
                y += lineHeight;
            }
            y += paragraphGap;
        }

        void image(ImageBlock block) {
            double scale = Math.min(1.0, Math.min((double) contentWidth / block.width(),
                    (double) contentHeight / block.height()));
            int w = (int) Math.round(block.width() * scale);
            int h = (int) Math.round(block.height() * scale);
            if (y + h > height - margin) {
                newSheet();
            }
            g.drawImage(block.image(), margin + (contentWidth - w) / 2, y, w, h, null);
            y += h + paragraphGap;
        }

        /** Grid with a shaded header row, repeated when the table continues on a new sheet. */
        void table(Table table) {
            if (table.isEmpty()) {
                return;
            }
            Font cellFont = body.deriveFont(body.getSize2D() * 0.8f);
            Font headFont = cellFont.deriveFont(Font.BOLD);
            g.setFont(cellFont);
            FontMetrics fm = g.getFontMetrics();
            int pad = Math.max(4, cellFont.getSize() / 3);
            FontMetrics boldMetrics = g.getFontMetrics(headFont); // wider face: header cells never break mid-word
            float[] widths = TableLayout.columnWidths(table, boldMetrics::stringWidth, contentWidth, pad);
            int lineHeight = (int) (fm.getHeight() * 1.15);
            for (int r = 0; r < table.rowCount(); r++) {
                boolean header = table.header() && r == 0;
                List<List<String>> cells = wrapRow(table, r, widths, pad, fm);
                int rowHeight = rowHeight(cells, lineHeight, pad);
                if (y + rowHeight > height - margin && y > margin) {
                    newSheet();
                    if (table.header() && r > 0) {
                        List<List<String>> head = wrapRow(table, 0, widths, pad, fm);
                        drawRow(head, widths, rowHeight(head, lineHeight, pad), lineHeight, pad, headFont, true);
                    }
                }
                drawRow(cells, widths, Math.min(rowHeight, contentHeight), lineHeight, pad, header ? headFont : cellFont, header);
            }
            y += paragraphGap * 2;
        }

        private List<List<String>> wrapRow(Table table, int r, float[] widths, int pad, FontMetrics fm) {
            List<List<String>> cells = new ArrayList<>();
            for (int c = 0; c < widths.length; c++) {
                cells.add(wrap(table.cell(r, c), fm, (int) widths[c] - 2 * pad));
            }
            return cells;
        }

        private static int rowHeight(List<List<String>> cells, int lineHeight, int pad) {
            return Math.max(1, cells.stream().mapToInt(List::size).max().orElse(1)) * lineHeight + 2 * pad;
        }

        private void drawRow(List<List<String>> cells, float[] widths, int rowHeight, int lineHeight, int pad,
                             Font font, boolean header) {
            int x = margin;
            int total = 0;
            for (float w : widths) {
                total += Math.round(w);
            }
            if (header) {
                g.setColor(new Color(0xEEF3FB));
                g.fillRect(margin, y, total, rowHeight);
            }
            g.setFont(font);
            FontMetrics fm = g.getFontMetrics();
            for (int c = 0; c < widths.length; c++) {
                int w = Math.round(widths[c]);
                g.setColor(Color.BLACK);
                int lineY = y + pad + fm.getAscent();
                for (String line : cells.get(c)) {
                    if (lineY > y + rowHeight) {
                        break;
                    }
                    g.drawString(line, x + pad, lineY);
                    lineY += lineHeight;
                }
                g.setColor(new Color(0xC9D3E0));
                g.drawRect(x, y, w, rowHeight);
                x += w;
            }
            g.setColor(Color.BLACK);
            y += rowHeight;
        }

        private List<String> wrap(String text, FontMetrics fm) {
            return wrap(text, fm, contentWidth);
        }

        private List<String> wrap(String text, FontMetrics fm, int maxWidth) {
            List<String> lines = new ArrayList<>();
            StringBuilder line = new StringBuilder();
            for (String word : text.split("\\s+")) {
                String candidate = line.isEmpty() ? word : line + " " + word;
                if (fm.stringWidth(candidate) <= maxWidth || line.isEmpty()) {
                    line.setLength(0);
                    line.append(candidate);
                } else {
                    lines.add(line.toString());
                    line.setLength(0);
                    line.append(word);
                }
            }
            if (!line.isEmpty()) {
                lines.add(line.toString());
            }
            return lines;
        }

        List<BufferedImage> finish() {
            g.dispose();
            return images;
        }
    }
}

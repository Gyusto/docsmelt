package com.docsmelt.io.image;

import com.docsmelt.model.Block;
import com.docsmelt.model.ImageBlock;
import com.docsmelt.model.Page;
import com.docsmelt.model.Paragraph;

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

        private List<String> wrap(String text, FontMetrics fm) {
            List<String> lines = new ArrayList<>();
            StringBuilder line = new StringBuilder();
            for (String word : text.split("\\s+")) {
                String candidate = line.isEmpty() ? word : line + " " + word;
                if (fm.stringWidth(candidate) <= contentWidth || line.isEmpty()) {
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

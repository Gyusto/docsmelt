package com.docsmelt.pdf;

import com.docsmelt.core.Progress;
import com.docsmelt.format.Format;
import com.docsmelt.io.ConversionException;
import com.docsmelt.io.pdf.FontChain;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.util.Matrix;

import java.awt.Color;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Adds text, highlights, rectangles, lines and arrows to PDF pages. Marks are
 * drawn into the page itself (not as removable annotations), so they look the
 * same in every viewer and print. Coordinates are fractions of the page as
 * displayed, with the origin top-left — rotated pages work like any other.
 */
public final class PdfAnnotator {

    public sealed interface Mark permits Text, Rect, Line {
        int page();

        /**
         * Parses the compact form the web UI sends, fields separated by '|':
         * <pre>
         *   text|page|x|y|size|#rrggbb|url-encoded text
         *   rect|page|x|y|w|h|#rrggbb|fill-or-stroke|opacity
         *   line|page|x1|y1|x2|y2|#rrggbb|width|arrow-or-plain
         * </pre>
         */
        static Mark parse(String spec) {
            String[] f = spec.split("\\|", -1);
            try {
                return switch (f[0]) {
                    case "text" -> new Text(i(f[1]), n(f[2]), n(f[3]), n(f[4]), color(f[5]),
                            URLDecoder.decode(f[6], StandardCharsets.UTF_8));
                    case "rect" -> new Rect(i(f[1]), n(f[2]), n(f[3]), n(f[4]), n(f[5]), color(f[6]), "fill".equals(f[7]), n(f[8]));
                    case "line" -> new Line(i(f[1]), n(f[2]), n(f[3]), n(f[4]), n(f[5]), color(f[6]), n(f[7]), "arrow".equals(f[8]));
                    default -> throw new IllegalArgumentException();
                };
            } catch (RuntimeException e) {
                if (e instanceof IllegalArgumentException iae && iae.getMessage() != null && !(e instanceof NumberFormatException)) {
                    throw iae;
                }
                throw new IllegalArgumentException("Invalid mark: " + spec);
            }
        }

        private static int i(String s) {
            return Integer.parseInt(s);
        }

        private static float n(String s) {
            return Float.parseFloat(s);
        }

        private static Color color(String hex) {
            String h = hex.replaceFirst("^#", "");
            if (!h.matches("[0-9a-fA-F]{6}")) {
                throw new IllegalArgumentException("Colour must look like #c73a2a");
            }
            return new Color(Integer.parseInt(h, 16));
        }
    }

    /** Text with its top-left corner at (x, y); {@code size} in points. Newlines start new lines. */
    public record Text(int page, float x, float y, float size, Color color, String text) implements Mark {
        public Text {
            check(page, x, y);
            if (size < 4 || size > 144) {
                throw new IllegalArgumentException("Text size must be between 4 and 144 points");
            }
            if (text.isBlank() || text.length() > 2000) {
                throw new IllegalArgumentException("Text must be 1–2000 characters");
            }
        }
    }

    /** A filled (e.g. highlight) or outlined rectangle. */
    public record Rect(int page, float x, float y, float w, float h, Color color, boolean filled, float opacity) implements Mark {
        public Rect {
            check(page, x, y);
            if (w <= 0 || h <= 0 || x + w > 1.001f || y + h > 1.001f) {
                throw new IllegalArgumentException("Rectangles must lie inside the page");
            }
            if (opacity < 0.05f || opacity > 1f) {
                throw new IllegalArgumentException("Opacity must be between 0.05 and 1");
            }
        }
    }

    /** A straight line, optionally with an arrowhead at (x2, y2); {@code width} in points. */
    public record Line(int page, float x1, float y1, float x2, float y2, Color color, float width, boolean arrow) implements Mark {
        public Line {
            check(page, x1, y1);
            check(page, x2, y2);
            if (width < 0.25f || width > 20) {
                throw new IllegalArgumentException("Line width must be between 0.25 and 20 points");
            }
        }
    }

    private static void check(int page, float x, float y) {
        if (page < 1) {
            throw new IllegalArgumentException("Page numbers start at 1");
        }
        if (x < 0 || x > 1 || y < 0 || y > 1) {
            throw new IllegalArgumentException("Positions are fractions of the page (0–1)");
        }
    }

    /** @return number of marks drawn */
    public int apply(Path source, Path target, List<Mark> marks) {
        if (Format.fromPath(source) != Format.PDF) {
            throw new ConversionException("Only PDF files can be annotated: " + source.getFileName());
        }
        if (source.toAbsolutePath().normalize().equals(target.toAbsolutePath().normalize())) {
            throw new ConversionException("Output would overwrite the input: " + source);
        }
        if (marks.isEmpty()) {
            throw new ConversionException("Add something to the page first");
        }
        Map<Integer, List<Mark>> byPage = new TreeMap<>();
        marks.forEach(m -> byPage.computeIfAbsent(m.page(), k -> new ArrayList<>()).add(m));
        try (PDDocument pdf = PdfMerger.load(source, source)) {
            int count = pdf.getNumberOfPages();
            FontChain font = FontChain.regular(pdf, List.of());
            int done = 0;
            for (var entry : byPage.entrySet()) {
                Progress.step(done++, byPage.size());
                if (entry.getKey() > count) {
                    throw new ConversionException("Page " + entry.getKey() + " does not exist (document has " + count + " pages)");
                }
                PDPage page = pdf.getPage(entry.getKey() - 1);
                draw(pdf, page, font, entry.getValue());
            }
            Files.createDirectories(target.toAbsolutePath().getParent());
            pdf.save(target.toFile());
            return marks.size();
        } catch (IOException e) {
            throw new ConversionException("Could not annotate " + source.getFileName() + ": " + e.getMessage(), e);
        }
    }

    private static void draw(PDDocument pdf, PDPage page, FontChain font, List<Mark> marks) throws IOException {
        PageGeometry geo = new PageGeometry(page);
        float W = geo.width();
        float H = geo.height();
        try (PDPageContentStream cs = new PDPageContentStream(pdf, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
            // From here on, draw in display coordinates: origin bottom-left of the page as seen.
            cs.transform(geo.place(0, 0, 1, 1));
            for (Mark mark : marks) {
                cs.saveGraphicsState();
                switch (mark) {
                    case Text t -> {
                        cs.setNonStrokingColor(t.color());
                        float lineHeight = t.size() * 1.25f;
                        float baseline = H - t.y() * H - t.size() * 0.9f;
                        for (String line : t.text().split("\\R")) {
                            if (!line.isEmpty()) {
                                cs.beginText();
                                cs.setTextMatrix(Matrix.getTranslateInstance(t.x() * W, baseline));
                                for (FontChain.Run run : font.runs(line)) {
                                    cs.setFont(run.font(), t.size());
                                    cs.showText(run.text());
                                }
                                cs.endText();
                            }
                            baseline -= lineHeight;
                        }
                    }
                    case Rect r -> {
                        PDExtendedGraphicsState gs = new PDExtendedGraphicsState();
                        gs.setNonStrokingAlphaConstant(r.opacity());
                        gs.setStrokingAlphaConstant(r.opacity());
                        cs.setGraphicsStateParameters(gs);
                        cs.addRect(r.x() * W, H - (r.y() + r.h()) * H, r.w() * W, r.h() * H);
                        if (r.filled()) {
                            cs.setNonStrokingColor(r.color());
                            cs.fill();
                        } else {
                            cs.setStrokingColor(r.color());
                            cs.setLineWidth(2f);
                            cs.stroke();
                        }
                    }
                    case Line l -> {
                        float x1 = l.x1() * W, y1 = H - l.y1() * H, x2 = l.x2() * W, y2 = H - l.y2() * H;
                        cs.setStrokingColor(l.color());
                        cs.setNonStrokingColor(l.color());
                        cs.setLineWidth(l.width());
                        cs.setLineCapStyle(1);
                        double angle = Math.atan2(y2 - y1, x2 - x1);
                        float head = Math.max(8f, l.width() * 4f);
                        // Stop the shaft at the arrowhead's base so the tip stays sharp.
                        float ex = l.arrow() ? (float) (x2 - Math.cos(angle) * head * 0.8) : x2;
                        float ey = l.arrow() ? (float) (y2 - Math.sin(angle) * head * 0.8) : y2;
                        cs.moveTo(x1, y1);
                        cs.lineTo(ex, ey);
                        cs.stroke();
                        if (l.arrow()) {
                            double spread = Math.toRadians(25);
                            cs.moveTo(x2, y2);
                            cs.lineTo((float) (x2 - head * Math.cos(angle - spread)), (float) (y2 - head * Math.sin(angle - spread)));
                            cs.lineTo((float) (x2 - head * Math.cos(angle + spread)), (float) (y2 - head * Math.sin(angle + spread)));
                            cs.closePath();
                            cs.fill();
                        }
                    }
                }
                cs.restoreGraphicsState();
            }
        }
    }
}

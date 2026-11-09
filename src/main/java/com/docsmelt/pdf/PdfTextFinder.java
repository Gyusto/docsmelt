package com.docsmelt.pdf;

import com.docsmelt.format.Format;
import com.docsmelt.io.ConversionException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Finds where text appears on the pages, as boxes in display coordinates
 * (fractions of the page, top-left origin) — the same form {@link PdfRedactor}
 * takes. Matching is case-insensitive; a match that wraps onto the next line
 * gives one box per line.
 */
public final class PdfTextFinder {

    static final int MAX_MATCHES = 2_000;

    public List<PdfRedactor.Box> find(Path source, String query) {
        if (Format.fromPath(source) != Format.PDF) {
            throw new ConversionException("Text can only be searched in PDFs");
        }
        String needle = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        if (needle.length() < 2) {
            throw new IllegalArgumentException("Search for at least 2 characters");
        }
        try (PDDocument pdf = PdfMerger.load(source, source)) {
            List<PdfRedactor.Box> boxes = new ArrayList<>();
            for (int p = 1; p <= pdf.getNumberOfPages() && boxes.size() < MAX_MATCHES; p++) {
                Collector collector = new Collector();
                collector.setStartPage(p);
                collector.setEndPage(p);
                collector.setSortByPosition(true);
                collector.writeText(pdf, new StringWriter());
                boxes.addAll(collector.matches(needle, p, new PageGeometry(pdf.getPage(p - 1))));
            }
            return boxes.size() > MAX_MATCHES ? boxes.subList(0, MAX_MATCHES) : boxes;
        } catch (IOException e) {
            throw new ConversionException("Could not search " + source.getFileName() + ": " + e.getMessage(), e);
        }
    }

    /** Records every character with its position; a null position marks a gap between text runs. */
    private static final class Collector extends PDFTextStripper {
        private final StringBuilder text = new StringBuilder();
        private final List<TextPosition> positions = new ArrayList<>();

        @Override
        protected void writeString(String string, List<TextPosition> textPositions) {
            for (TextPosition tp : textPositions) {
                for (char c : tp.getUnicode().toCharArray()) {
                    text.append(Character.toLowerCase(c));
                    positions.add(tp);
                }
            }
            text.append(' ');
            positions.add(null);
        }

        List<PdfRedactor.Box> matches(String needle, int page, PageGeometry geo) {
            List<PdfRedactor.Box> boxes = new ArrayList<>();
            String haystack = text.toString();
            for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
                boxes.addAll(boxesFor(i, i + needle.length(), page, geo));
            }
            return boxes;
        }

        /** One box per line the match covers. */
        private List<PdfRedactor.Box> boxesFor(int from, int to, int page, PageGeometry geo) {
            List<PdfRedactor.Box> boxes = new ArrayList<>();
            float[] acc = null; // minU, minV, maxU, maxV in display space
            float baseline = Float.NaN;
            for (int i = from; i < to; i++) {
                TextPosition tp = positions.get(i);
                if (tp == null) {
                    continue;
                }
                if (!Float.isNaN(baseline) && Math.abs(tp.getYDirAdj() - baseline) > tp.getHeightDir() * 0.5f) {
                    boxes.add(box(page, acc, geo)); // the match continues on a new line
                    acc = null;
                }
                baseline = tp.getYDirAdj();
                acc = union(acc, glyphBox(tp, geo));
            }
            if (acc != null) {
                boxes.add(box(page, acc, geo));
            }
            return boxes;
        }

        /**
         * The glyph's box in page space (from its text matrix, relative to the
         * crop box), converted corner by corner to display space. This works
         * whatever the page rotation or the text direction.
         */
        private static float[] glyphBox(TextPosition tp, PageGeometry geo) {
            var m = tp.getTextMatrix();
            float height = Math.max(tp.getHeightDir(), tp.getFontSizeInPt() * 0.7f);
            float width = tp.getWidthDirAdj();
            // Along the text direction (dx, dy) and perpendicular to it (px, py).
            double angle = Math.atan2(m.getShearY(), m.getScaleX());
            float dx = (float) Math.cos(angle), dy = (float) Math.sin(angle);
            float px = -dy, py = dx;
            float x = m.getTranslateX(), y = m.getTranslateY();
            float below = height * 0.3f, above = height * 1.15f;
            float[][] corners = {
                    {x - px * below, y - py * below},
                    {x + dx * width - px * below, y + dy * width - py * below},
                    {x + px * above, y + py * above},
                    {x + dx * width + px * above, y + dy * width + py * above}};
            float[] acc = null;
            for (float[] c : corners) {
                float[] d = geo.toDisplay(c[0], c[1]);
                acc = union(acc, new float[]{d[0], d[1], d[0], d[1]});
            }
            return acc;
        }

        private static float[] union(float[] a, float[] b) {
            if (a == null) {
                return b;
            }
            return new float[]{Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.max(a[2], b[2]), Math.max(a[3], b[3])};
        }

        private static PdfRedactor.Box box(int page, float[] d, PageGeometry geo) {
            float pad = 1.5f;
            float W = geo.width(), H = geo.height();
            float l = clamp((d[0] - pad) / W);
            float r = clamp((d[2] + pad) / W);
            float t = clamp((H - d[3] - pad) / H); // display v grows upwards; boxes are top-down
            float b = clamp((H - d[1] + pad) / H);
            return new PdfRedactor.Box(page, l, t, Math.max(r - l, 0.001f), Math.max(b - t, 0.001f));
        }

        private static float clamp(float v) {
            return Math.min(1f, Math.max(0f, v));
        }
    }
}

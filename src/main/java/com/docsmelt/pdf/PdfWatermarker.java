package com.docsmelt.pdf;

import com.docsmelt.format.Format;
import com.docsmelt.io.ConversionException;
import com.docsmelt.io.PageRange;
import com.docsmelt.io.image.ImageLoader;
import com.docsmelt.io.pdf.FontChain;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.util.Matrix;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Stamps a text or image watermark on PDF pages: centred (optionally at an
 * angle) or tiled across the page. Positions are as displayed, so rotated
 * pages get an upright watermark.
 */
public final class PdfWatermarker {

    public enum Layout {
        CENTER, TILE;

        public static Layout parse(String s) {
            try {
                return valueOf(s.strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Layout must be center or tile, got '" + s + "'");
            }
        }
    }

    /** Text watermark, or an image when {@code image} is set. */
    public record Options(String text, Path image, Color color, float opacity, float angle,
                          float size, Layout layout, PageRange pages) {

        public static Options text(String text) {
            return new Options(text, null, new Color(0xC73A2A), 0.25f, 45, 0, Layout.CENTER, PageRange.ALL);
        }

        public Options {
            if (image == null && (text == null || text.isBlank())) {
                throw new IllegalArgumentException("Enter the watermark text or choose an image");
            }
            if (text != null && text.length() > 80) {
                throw new IllegalArgumentException("Watermark text is too long (max 80 characters)");
            }
            if (opacity < 0.05f || opacity > 1f) {
                throw new IllegalArgumentException("Opacity must be between 0.05 and 1");
            }
            if (angle < -90 || angle > 90) {
                throw new IllegalArgumentException("Angle must be between -90 and 90 degrees");
            }
            if (size < 0 || size > 1) {
                throw new IllegalArgumentException("Size must be between 0 and 1 (0 = automatic)");
            }
        }
    }

    /** @return number of pages watermarked */
    public int apply(Path source, Path target, Options o) {
        if (Format.fromPath(source) != Format.PDF) {
            throw new ConversionException("Only PDF files can be watermarked: " + source.getFileName());
        }
        if (source.toAbsolutePath().normalize().equals(target.toAbsolutePath().normalize())) {
            throw new ConversionException("Output would overwrite the input: " + source);
        }
        BufferedImage logo = o.image() == null ? null : readImage(o.image());
        try (PDDocument pdf = PdfMerger.load(source, source)) {
            FontChain font = logo == null ? FontChain.bold(pdf, List.of()) : null;
            PDImageXObject xLogo = logo == null ? null : LosslessFactory.createFromImage(pdf, logo);
            PDExtendedGraphicsState alpha = new PDExtendedGraphicsState();
            alpha.setNonStrokingAlphaConstant(o.opacity());
            alpha.setStrokingAlphaConstant(o.opacity());

            int total = pdf.getNumberOfPages();
            int stamped = 0;
            for (int i = 0; i < total; i++) {
                if (!o.pages().includes(i + 1)) {
                    continue;
                }
                PDPage page = pdf.getPage(i);
                try (PDPageContentStream cs = new PDPageContentStream(pdf, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                    cs.setGraphicsStateParameters(alpha);
                    Stamp stamp = logo != null ? new ImageStamp(xLogo, logo) : new TextStamp(font, o.text().strip(), o.color());
                    stampPage(cs, new PageGeometry(page), stamp, o);
                }
                stamped++;
            }
            o.pages().requireAny(stamped, total);
            Files.createDirectories(target.toAbsolutePath().getParent());
            pdf.save(target.toFile());
            return stamped;
        } catch (IOException e) {
            throw new ConversionException("Could not watermark " + source.getFileName() + ": " + e.getMessage(), e);
        }
    }

    /** Something drawable with a known natural size, centred on a point. */
    private sealed interface Stamp permits TextStamp, ImageStamp {
        /** Natural width/height at scale 1 (text: per point of font size). */
        float width();

        float height();

        void draw(PDPageContentStream cs, Matrix centreTransform, float scale) throws IOException;
    }

    private record TextStamp(FontChain font, String text, Color color) implements Stamp {
        public float width() {
            try {
                return font.width(text, 1f);
            } catch (IOException e) {
                return text.length() * 0.6f;
            }
        }

        public float height() {
            return 0.72f; // cap height of a 1-pt font, roughly
        }

        public void draw(PDPageContentStream cs, Matrix m, float fontSize) throws IOException {
            cs.setNonStrokingColor(color);
            cs.beginText();
            // Text origin is the baseline-left; shift so the text's centre sits on the point.
            Matrix shifted = m.clone();
            shifted.concatenate(Matrix.getTranslateInstance(-width() * fontSize / 2, -height() * fontSize / 2));
            cs.setTextMatrix(shifted);
            for (FontChain.Run run : font.runs(text)) {
                cs.setFont(run.font(), fontSize);
                cs.showText(run.text());
            }
            cs.endText();
        }
    }

    private record ImageStamp(PDImageXObject image, BufferedImage source) implements Stamp {
        public float width() {
            return source.getWidth();
        }

        public float height() {
            return source.getHeight();
        }

        public void draw(PDPageContentStream cs, Matrix m, float scale) throws IOException {
            Matrix shifted = m.clone();
            shifted.concatenate(Matrix.getTranslateInstance(-width() * scale / 2, -height() * scale / 2));
            shifted.concatenate(Matrix.getScaleInstance(width() * scale, height() * scale));
            cs.drawImage(image, shifted);
        }
    }

    private static void stampPage(PDPageContentStream cs, PageGeometry geo, Stamp stamp, Options o) throws IOException {
        float pw = geo.width();
        float ph = geo.height();
        double radians = Math.toRadians(o.angle());
        float cos = (float) Math.abs(Math.cos(radians));
        float sin = (float) Math.abs(Math.sin(radians));

        if (o.layout() == Layout.TILE) {
            // Fixed comfortable size, repeated on a staggered grid.
            float target = o.size() > 0 ? o.size() * pw : pw / 3.2f;
            float scale = target / stamp.width();
            float cellW = target * 1.35f;
            float cellH = Math.max(stamp.height() * scale * 4.5f, pw / 5f);
            int row = 0;
            for (float v = cellH / 2; v < ph + cellH; v += cellH, row++) {
                float offset = (row % 2) * cellW / 2;
                for (float u = -cellW / 2 + offset; u < pw + cellW; u += cellW) {
                    stamp.draw(cs, centre(geo, u, v, radians), scale);
                }
            }
            return;
        }
        // Centre: as large as fits the page diagonal-wise (or the requested share of the width).
        float scale;
        if (o.size() > 0) {
            scale = o.size() * pw / stamp.width();
        } else {
            float fitW = (pw * 0.8f) / (stamp.width() * cos + stamp.height() * sin);
            float fitH = (ph * 0.8f) / (stamp.width() * sin + stamp.height() * cos);
            scale = Math.min(fitW, fitH);
            if (stamp instanceof TextStamp) {
                scale = Math.min(scale, 120f); // short words shouldn't become gigantic
            }
        }
        stamp.draw(cs, centre(geo, pw / 2, ph / 2, radians), scale);
    }

    /** Transform putting the origin at display point (u, v), rotated counter-clockwise by {@code radians}. */
    private static Matrix centre(PageGeometry geo, float u, float v, double radians) {
        Matrix m = geo.place(u, v, 1, 1);
        m.concatenate(Matrix.getRotateInstance(radians, 0, 0));
        return m;
    }

    private static BufferedImage readImage(Path path) {
        try {
            BufferedImage img = ImageLoader.read(path);
            if (img == null) {
                throw new ConversionException("Not a readable image: " + path.getFileName());
            }
            if (img.getWidth() > 5000 || img.getHeight() > 5000) {
                throw new ConversionException("The watermark image is too large (max 5000×5000 pixels)");
            }
            return img;
        } catch (IOException e) {
            throw new ConversionException("Could not read watermark image: " + e.getMessage(), e);
        }
    }
}

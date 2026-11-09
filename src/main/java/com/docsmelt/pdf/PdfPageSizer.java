package com.docsmelt.pdf;

import com.docsmelt.format.Format;
import com.docsmelt.io.ConversionException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.util.Matrix;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Crops and resizes PDF pages.
 * <ul>
 *   <li><b>Trim margins</b> automatically (finds the content on each page) or by
 *       a fixed amount — only the visible area changes, nothing is deleted.</li>
 *   <li><b>Resize</b> to a paper size: content is scaled to fit and centred;
 *       landscape pages stay landscape.</li>
 * </ul>
 */
public final class PdfPageSizer {

    public enum Paper {
        A3(PDRectangle.A3), A4(PDRectangle.A4), A5(PDRectangle.A5), LETTER(PDRectangle.LETTER), LEGAL(PDRectangle.LEGAL);

        final PDRectangle size;

        Paper(PDRectangle size) {
            this.size = size;
        }

        public static Paper parse(String s) {
            try {
                return valueOf(s.strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Paper must be a3, a4, a5, letter or legal, got '" + s + "'");
            }
        }
    }

    private static final float MM = 72f / 25.4f;
    private static final float AUTO_PADDING = 12f;   // points kept around detected content
    private static final float ANALYSIS_DPI = 50f;
    private static final int WHITE_THRESHOLD = 245;

    /**
     * @param marginMm fixed margin to cut from every side, or a negative value for automatic trimming
     * @return number of pages changed
     */
    public int crop(Path source, Path target, float marginMm) {
        check(source, target);
        if (marginMm > 100) {
            throw new IllegalArgumentException("Margin must be at most 100 mm");
        }
        try (PDDocument pdf = PdfMerger.load(source, source)) {
            int changed = 0;
            PDFRenderer renderer = marginMm < 0 ? new PDFRenderer(pdf) : null;
            for (int i = 0; i < pdf.getNumberOfPages(); i++) {
                PDPage page = pdf.getPage(i);
                PDRectangle box = page.getCropBox();
                PDRectangle cropped = marginMm < 0 ? contentBox(renderer, page, i) : inset(box, marginMm * MM);
                if (cropped != null && cropped.getWidth() > 36 && cropped.getHeight() > 36 && !same(cropped, box)) {
                    page.setCropBox(cropped);
                    changed++;
                }
            }
            save(pdf, target);
            return changed;
        } catch (IOException e) {
            throw new ConversionException("Could not crop " + source.getFileName() + ": " + e.getMessage(), e);
        }
    }

    /** @return number of pages resized */
    public int resize(Path source, Path target, Paper paper) {
        check(source, target);
        try (PDDocument pdf = PdfMerger.load(source, source)) {
            for (PDPage page : pdf.getPages()) {
                PDRectangle box = page.getCropBox();
                boolean landscape = box.getWidth() > box.getHeight();
                float tw = landscape ? paper.size.getHeight() : paper.size.getWidth();
                float th = landscape ? paper.size.getWidth() : paper.size.getHeight();
                float scale = Math.min(tw / box.getWidth(), th / box.getHeight());
                float dx = (tw - box.getWidth() * scale) / 2;
                float dy = (th - box.getHeight() * scale) / 2;

                // Run before the existing content: everything drawn afterwards is moved and scaled.
                try (PDPageContentStream cs = new PDPageContentStream(pdf, page, PDPageContentStream.AppendMode.PREPEND, true)) {
                    cs.transform(new Matrix(scale, 0, 0, scale, dx - box.getLowerLeftX() * scale, dy - box.getLowerLeftY() * scale));
                }
                PDRectangle newBox = new PDRectangle(tw, th);
                page.setMediaBox(newBox);
                page.setCropBox(newBox);
                page.setBleedBox(null);
                page.setTrimBox(null);
                page.setArtBox(null);
            }
            save(pdf, target);
            return pdf.getNumberOfPages();
        } catch (IOException e) {
            throw new ConversionException("Could not resize " + source.getFileName() + ": " + e.getMessage(), e);
        }
    }

    /** Finds the non-white area of a page (rendered unrotated), in user space. Null for blank pages. */
    private static PDRectangle contentBox(PDFRenderer renderer, PDPage page, int index) throws IOException {
        int rotation = page.getRotation();
        page.setRotation(0); // analyse in the page's own coordinates
        BufferedImage img;
        try {
            img = renderer.renderImageWithDPI(index, ANALYSIS_DPI, ImageType.GRAY);
        } finally {
            page.setRotation(rotation);
        }
        int w = img.getWidth();
        int h = img.getHeight();
        int minX = w, minY = h, maxX = -1, maxY = -1;
        var raster = img.getRaster();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (raster.getSample(x, y, 0) < WHITE_THRESHOLD) {
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        if (maxX < 0) {
            return null;
        }
        PDRectangle box = page.getCropBox();
        float px = 72f / ANALYSIS_DPI;
        float llx = box.getLowerLeftX() + minX * px - AUTO_PADDING;
        float urx = box.getLowerLeftX() + (maxX + 1) * px + AUTO_PADDING;
        float ury = box.getUpperRightY() - minY * px + AUTO_PADDING;
        float lly = box.getUpperRightY() - (maxY + 1) * px - AUTO_PADDING;
        PDRectangle result = new PDRectangle();
        result.setLowerLeftX(Math.max(llx, box.getLowerLeftX()));
        result.setLowerLeftY(Math.max(lly, box.getLowerLeftY()));
        result.setUpperRightX(Math.min(urx, box.getUpperRightX()));
        result.setUpperRightY(Math.min(ury, box.getUpperRightY()));
        return result;
    }

    private static PDRectangle inset(PDRectangle box, float by) {
        PDRectangle r = new PDRectangle();
        r.setLowerLeftX(box.getLowerLeftX() + by);
        r.setLowerLeftY(box.getLowerLeftY() + by);
        r.setUpperRightX(box.getUpperRightX() - by);
        r.setUpperRightY(box.getUpperRightY() - by);
        return r;
    }

    private static boolean same(PDRectangle a, PDRectangle b) {
        return Math.abs(a.getLowerLeftX() - b.getLowerLeftX()) < 0.5 && Math.abs(a.getLowerLeftY() - b.getLowerLeftY()) < 0.5
                && Math.abs(a.getUpperRightX() - b.getUpperRightX()) < 0.5 && Math.abs(a.getUpperRightY() - b.getUpperRightY()) < 0.5;
    }

    private static void check(Path source, Path target) {
        if (Format.fromPath(source) != Format.PDF) {
            throw new ConversionException("Only PDF pages can be cropped or resized: " + source.getFileName());
        }
        if (source.toAbsolutePath().normalize().equals(target.toAbsolutePath().normalize())) {
            throw new ConversionException("Output would overwrite the input: " + source);
        }
    }

    private static void save(PDDocument pdf, Path target) throws IOException {
        Files.createDirectories(target.toAbsolutePath().getParent());
        pdf.save(target.toFile());
    }
}

package com.docsmelt.pdf;

import com.docsmelt.core.Progress;
import com.docsmelt.format.Format;
import com.docsmelt.io.ConversionException;
import com.docsmelt.io.Limits;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Permanently removes content under black boxes.
 *
 * <p>Drawing a black rectangle over text is not redaction: the text is still
 * in the file and can be copied out. Here every page with a box is rendered to
 * an image, the boxes are burned into the pixels, and the original page
 * (text, images, links, form fields) is replaced by that image. Pages without
 * boxes are left untouched. The redacted pages are no longer text-searchable;
 * run OCR afterwards if that matters.
 */
public final class PdfRedactor {

    /** A box on a page as displayed: {@code x, y} = top-left, all fractions of the page (0–1). */
    public record Box(int page, float x, float y, float w, float h) {
        public Box {
            if (page < 1) {
                throw new IllegalArgumentException("Page numbers start at 1");
            }
            if (x < 0 || y < 0 || w <= 0 || h <= 0 || x + w > 1.0001f || y + h > 1.0001f) {
                throw new IllegalArgumentException("Redaction boxes must lie inside the page (fractions 0–1)");
            }
        }

        /** Parses "page:x,y,w,h". */
        public static Box parse(String spec) {
            try {
                String[] pageAndRect = spec.strip().split(":", 2);
                String[] v = pageAndRect[1].split(",");
                return new Box(Integer.parseInt(pageAndRect[0].strip()), Float.parseFloat(v[0]), Float.parseFloat(v[1]),
                        Float.parseFloat(v[2]), Float.parseFloat(v[3]));
            } catch (RuntimeException e) {
                if (e instanceof IllegalArgumentException iae && !(e instanceof NumberFormatException)) {
                    throw iae;
                }
                throw new IllegalArgumentException("Invalid box '" + spec + "' (use page:x,y,w,h with fractions, e.g. 1:0.1,0.2,0.3,0.05)");
            }
        }
    }

    private static final float DPI = 200f;

    /** @return number of pages redacted */
    public int redact(Path source, Path target, List<Box> boxes, boolean removeMetadata) {
        if (Format.fromPath(source) != Format.PDF) {
            throw new ConversionException("Only PDF files can be redacted: " + source.getFileName());
        }
        if (source.toAbsolutePath().normalize().equals(target.toAbsolutePath().normalize())) {
            throw new ConversionException("Output would overwrite the input: " + source);
        }
        if (boxes.isEmpty() && !removeMetadata) {
            throw new ConversionException("Mark at least one area to redact");
        }
        Map<Integer, List<Box>> byPage = new TreeMap<>();
        boxes.forEach(b -> byPage.computeIfAbsent(b.page(), k -> new ArrayList<>()).add(b));

        try (PDDocument pdf = PdfMerger.load(source, source)) {
            int count = pdf.getNumberOfPages();
            for (int page : byPage.keySet()) {
                if (page > count) {
                    throw new ConversionException("Page " + page + " does not exist (document has " + count + " pages)");
                }
            }
            PDFRenderer renderer = new PDFRenderer(pdf);
            List<PDPage> originals = new ArrayList<>();
            pdf.getPages().forEach(originals::add);
            int done = 0;
            for (var entry : byPage.entrySet()) {
                Progress.step(done++, byPage.size());
                PDPage old = originals.get(entry.getKey() - 1);
                PDPage flat = flatten(pdf, renderer, entry.getKey() - 1, old, entry.getValue());
                pdf.getPages().insertBefore(flat, old);
                pdf.getPages().remove(old);
            }
            if (removeMetadata) {
                pdf.setDocumentInformation(new PDDocumentInformation());
                pdf.getDocumentCatalog().setMetadata(null);
                pdf.getDocumentCatalog().getCOSObject().removeItem(COSName.PIECE_INFO);
            }
            Files.createDirectories(target.toAbsolutePath().getParent());
            pdf.save(target.toFile());
            return byPage.size();
        } catch (IOException e) {
            throw new ConversionException("Could not redact " + source.getFileName() + ": " + e.getMessage(), e);
        }
    }

    /** Renders the page as shown, burns in the boxes, and returns a new image-only page. */
    private static PDPage flatten(PDDocument pdf, PDFRenderer renderer, int index, PDPage page, List<Box> boxes) throws IOException {
        PageGeometry geo = new PageGeometry(page);
        float dpi = Limits.renderDpi(geo.width(), geo.height(), DPI);
        BufferedImage img = renderer.renderImageWithDPI(index, dpi, ImageType.RGB); // rotation applied
        Graphics2D g = img.createGraphics();
        g.setColor(Color.BLACK);
        int w = img.getWidth();
        int h = img.getHeight();
        for (Box b : boxes) {
            // Round outwards so no sliver of the content survives at the edges.
            int x0 = (int) Math.floor(b.x() * w) - 1;
            int y0 = (int) Math.floor(b.y() * h) - 1;
            int x1 = (int) Math.ceil((b.x() + b.w()) * w) + 1;
            int y1 = (int) Math.ceil((b.y() + b.h()) * h) + 1;
            g.fillRect(Math.max(0, x0), Math.max(0, y0), Math.min(w, x1) - Math.max(0, x0), Math.min(h, y1) - Math.max(0, y0));
        }
        g.dispose();

        PDRectangle box = new PDRectangle(geo.width(), geo.height());
        PDPage flat = new PDPage(box);
        try (PDPageContentStream cs = new PDPageContentStream(pdf, flat)) {
            cs.drawImage(JPEGFactory.createFromImage(pdf, img, 0.92f), 0, 0, box.getWidth(), box.getHeight());
        }
        return flat;
    }
}

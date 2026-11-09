package com.docsmelt.pdf;

import com.docsmelt.format.Format;
import com.docsmelt.io.ConversionException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Places a signature image (typically a transparent PNG) on a PDF page.
 *
 * <p>This is a <em>visual</em> signature, like signing a printout: it does not
 * add a cryptographic digital signature.
 */
public final class PdfSigner {

    /**
     * Where the signature goes, as fractions of the page <em>as displayed</em>:
     * {@code x}, {@code y} = top-left corner measured from the top-left of the
     * page, {@code width} = signature width. Height follows the image's aspect ratio.
     *
     * @param page 1-based; 0 means the last page
     */
    public record Placement(int page, float x, float y, float width) {
        /** Bottom right of the last page, where signatures usually go. */
        public static Placement defaults() {
            return new Placement(0, 0.6f, 0.8f, 0.3f);
        }

        public Placement {
            if (page < 0) {
                throw new IllegalArgumentException("Page must be 1 or more (0 = last page)");
            }
            if (x < 0 || x > 1 || y < 0 || y > 1) {
                throw new IllegalArgumentException("Position must be between 0 and 1 (fractions of the page)");
            }
            if (width < 0.02f || width > 1) {
                throw new IllegalArgumentException("Width must be between 0.02 and 1 (fraction of the page width)");
            }
        }
    }

    /** @return the page number that was signed */
    public int sign(Path source, Path signature, Path target, Placement placement) {
        if (Format.fromPath(source) != Format.PDF) {
            throw new ConversionException("Only PDF files can be signed: " + source.getFileName());
        }
        if (source.toAbsolutePath().normalize().equals(target.toAbsolutePath().normalize())) {
            throw new ConversionException("Output would overwrite the input: " + source);
        }
        BufferedImage image = readSignature(signature);
        try (PDDocument pdf = PdfMerger.load(source, source)) {
            int count = pdf.getNumberOfPages();
            int pageNumber = placement.page() == 0 ? count : placement.page();
            if (pageNumber > count) {
                throw new ConversionException("Page " + pageNumber + " does not exist (document has " + count + " pages)");
            }
            PDPage page = pdf.getPage(pageNumber - 1);
            PageGeometry geo = new PageGeometry(page);

            float w = placement.width() * geo.width();
            float h = w * image.getHeight() / image.getWidth();
            float u = Math.min(placement.x() * geo.width(), geo.width() - w);
            float top = placement.y() * geo.height();
            float v = Math.max(0, geo.height() - top - h);

            PDImageXObject xObject = LosslessFactory.createFromImage(pdf, image); // keeps transparency
            try (PDPageContentStream cs = new PDPageContentStream(pdf, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                cs.drawImage(xObject, geo.place(u, v, w, h));
            }
            Files.createDirectories(target.toAbsolutePath().getParent());
            pdf.save(target.toFile());
            return pageNumber;
        } catch (IOException e) {
            throw new ConversionException("Could not sign " + source.getFileName() + ": " + e.getMessage(), e);
        }
    }

    private static BufferedImage readSignature(Path signature) {
        if (!Format.fromPath(signature).isImage()) {
            throw new ConversionException("The signature must be an image (PNG with a transparent background works best)");
        }
        try {
            BufferedImage img = ImageIO.read(signature.toFile());
            if (img == null) {
                throw new ConversionException("Not a readable image: " + signature.getFileName());
            }
            if (img.getWidth() > 4000 || img.getHeight() > 4000) {
                throw new ConversionException("The signature image is too large (max 4000×4000 pixels)");
            }
            return img;
        } catch (IOException e) {
            throw new ConversionException("Could not read signature image: " + e.getMessage(), e);
        }
    }
}

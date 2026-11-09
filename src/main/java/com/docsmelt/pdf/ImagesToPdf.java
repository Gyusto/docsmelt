package com.docsmelt.pdf;

import com.docsmelt.format.Format;
import com.docsmelt.io.ConversionException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Puts each image on its own PDF page. JPEGs are embedded as-is (no
 * re-compression, no quality loss); other formats are embedded losslessly.
 */
public final class ImagesToPdf {

    /** Paper size; {@link #FIT} makes each page exactly the size of its image. */
    public enum PageSize {
        A4(PDRectangle.A4), LETTER(PDRectangle.LETTER), FIT(null);

        private final PDRectangle paper;

        PageSize(PDRectangle paper) {
            this.paper = paper;
        }

        public static PageSize parse(String s) {
            try {
                return valueOf(s.strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Page size must be a4, letter or fit, got '" + s + "'");
            }
        }
    }

    /** Image pixels per inch assumed for {@link PageSize#FIT} (the usual screen resolution). */
    private static final float FIT_DPI = 96f;

    /** @return number of pages written */
    public int create(List<Path> images, Path target, PageSize size, float marginPt) {
        if (images.isEmpty()) {
            throw new ConversionException("No images given");
        }
        if (marginPt < 0 || marginPt > 144) {
            throw new IllegalArgumentException("Margin must be between 0 and 144 points");
        }
        try (PDDocument pdf = new PDDocument()) {
            for (Path image : images) {
                addPage(pdf, image, size, marginPt);
            }
            Files.createDirectories(target.toAbsolutePath().getParent());
            pdf.save(target.toFile());
            return images.size();
        } catch (IOException e) {
            throw new ConversionException("Could not create PDF: " + e.getMessage(), e);
        }
    }

    private static void addPage(PDDocument pdf, Path imagePath, PageSize size, float margin) throws IOException {
        Format format = Format.fromPath(imagePath);
        if (!format.isImage()) {
            throw new ConversionException("Not an image: " + imagePath.getFileName());
        }
        PDImageXObject image = load(pdf, imagePath, format);
        float w = image.getWidth();
        float h = image.getHeight();

        PDRectangle box;
        if (size == PageSize.FIT) {
            float scale = 72f / FIT_DPI;
            box = new PDRectangle(w * scale + 2 * margin, h * scale + 2 * margin);
        } else {
            boolean landscape = w > h;
            PDRectangle paper = size.paper;
            box = landscape ? new PDRectangle(paper.getHeight(), paper.getWidth()) : paper;
        }

        PDPage page = new PDPage(box);
        pdf.addPage(page);
        float availW = box.getWidth() - 2 * margin;
        float availH = box.getHeight() - 2 * margin;
        float scale = Math.min(availW / w, availH / h);
        float drawW = w * scale;
        float drawH = h * scale;
        try (PDPageContentStream content = new PDPageContentStream(pdf, page)) {
            content.drawImage(image, margin + (availW - drawW) / 2, margin + (availH - drawH) / 2, drawW, drawH);
        }
    }

    private static PDImageXObject load(PDDocument pdf, Path path, Format format) throws IOException {
        if (format == Format.JPEG) {
            try (InputStream in = Files.newInputStream(path)) {
                return JPEGFactory.createFromStream(pdf, in); // original bytes, no quality loss
            } catch (IOException | RuntimeException e) {
                // fall through: unusual JPEG variants are decoded and re-embedded below
            }
        }
        BufferedImage img = ImageIO.read(path.toFile());
        if (img == null) {
            throw new ConversionException("Not a readable image: " + path.getFileName());
        }
        return LosslessFactory.createFromImage(pdf, img);
    }
}

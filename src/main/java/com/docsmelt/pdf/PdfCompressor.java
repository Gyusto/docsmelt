package com.docsmelt.pdf;

import com.docsmelt.format.Format;
import com.docsmelt.io.ConversionException;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Makes PDFs smaller by downscaling and re-encoding embedded images as JPEG,
 * then saving with compressed object streams. Text and vector content are
 * untouched. An image is only replaced when the new version is smaller, and
 * the whole file is never made bigger.
 */
public final class PdfCompressor {

    public enum Level {
        /** Barely visible changes. */
        LOW(0.85f, 200),
        /** Good for screens and email. */
        MEDIUM(0.70f, 150),
        /** Smallest files, softer images. */
        HIGH(0.50f, 96);

        final float jpegQuality;
        final int maxDpi;

        Level(float jpegQuality, int maxDpi) {
            this.jpegQuality = jpegQuality;
            this.maxDpi = maxDpi;
        }

        public static Level parse(String s) {
            try {
                return valueOf(s.strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Compression level must be low, medium or high, got '" + s + "'");
            }
        }
    }

    public record Result(long originalBytes, long compressedBytes, int imagesRecompressed) {
        public int savedPercent() {
            return originalBytes == 0 ? 0 : (int) Math.round(100.0 * (originalBytes - compressedBytes) / originalBytes);
        }
    }

    private static final int MIN_PIXELS = 64 * 64; // tiny images (icons, bullets) aren't worth it

    public Result compress(Path source, Path target, Level level) {
        if (Format.fromPath(source) != Format.PDF) {
            throw new ConversionException("Only PDF files can be compressed: " + source);
        }
        if (source.toAbsolutePath().normalize().equals(target.toAbsolutePath().normalize())) {
            throw new ConversionException("Output would overwrite the input: " + source);
        }
        try (PDDocument pdf = PdfMerger.load(source, source)) {
            Run run = new Run(pdf, level);
            for (PDPage page : pdf.getPages()) {
                PDRectangle box = page.getMediaBox();
                int maxW = Math.round(box.getWidth() / 72f * level.maxDpi);
                int maxH = Math.round(box.getHeight() / 72f * level.maxDpi);
                // A rotated page can show the image either way round.
                run.process(page.getResources(), Math.max(maxW, maxH), Math.max(maxW, maxH));
            }
            Files.createDirectories(target.toAbsolutePath().getParent());
            pdf.save(target.toFile());

            long before = Files.size(source);
            long after = Files.size(target);
            if (after >= before) {
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                return new Result(before, before, 0);
            }
            return new Result(before, after, run.recompressed);
        } catch (IOException e) {
            throw new ConversionException("Could not compress " + source + ": " + e.getMessage(), e);
        }
    }

    /** State for one compression run: images shared across pages are handled once. */
    private static final class Run {
        private final PDDocument pdf;
        private final Level level;
        private final Map<COSBase, PDImageXObject> replacements = new IdentityHashMap<>();
        private final Set<COSBase> visitedForms = Collections.newSetFromMap(new IdentityHashMap<>());
        private int recompressed;

        Run(PDDocument pdf, Level level) {
            this.pdf = pdf;
            this.level = level;
        }

        void process(PDResources resources, int maxW, int maxH) throws IOException {
            if (resources == null) {
                return;
            }
            for (COSName name : resources.getXObjectNames()) {
                PDXObject xObject;
                try {
                    xObject = resources.getXObject(name);
                } catch (IOException e) {
                    continue; // broken object: leave it alone
                }
                if (xObject instanceof PDImageXObject image) {
                    COSBase key = image.getCOSObject();
                    if (!replacements.containsKey(key)) {
                        replacements.put(key, shrink(image, maxW, maxH));
                    }
                    PDImageXObject replacement = replacements.get(key);
                    if (replacement != null) {
                        resources.put(name, replacement);
                    }
                } else if (xObject instanceof PDFormXObject form && visitedForms.add(form.getCOSObject())) {
                    process(form.getResources(), maxW, maxH);
                }
            }
        }

        /** @return a smaller replacement, or null to keep the original */
        private PDImageXObject shrink(PDImageXObject image, int maxW, int maxH) {
            try {
                if (image.isStencil() || image.getBitsPerComponent() == 1
                        || (long) image.getWidth() * image.getHeight() < MIN_PIXELS) {
                    return null;
                }
                BufferedImage original = image.getImage();
                boolean gray = image.getColorSpace().getNumberOfComponents() == 1 && !original.getColorModel().hasAlpha();
                BufferedImage scaled = scale(original, maxW, maxH, gray);
                PDImageXObject candidate = JPEGFactory.createFromImage(pdf, scaled, level.jpegQuality);
                if (candidate.getCOSObject().getLength() >= image.getCOSObject().getLength()) {
                    return null;
                }
                recompressed++;
                return candidate;
            } catch (IOException | RuntimeException e) {
                return null; // exotic encodings (JBIG2, JPX without codec, ...) stay as they are
            }
        }

        private static BufferedImage scale(BufferedImage src, int maxW, int maxH, boolean gray) {
            double factor = Math.min(1.0, Math.min((double) maxW / src.getWidth(), (double) maxH / src.getHeight()));
            int w = Math.max(1, (int) Math.round(src.getWidth() * factor));
            int h = Math.max(1, (int) Math.round(src.getHeight() * factor));
            int type = gray ? BufferedImage.TYPE_BYTE_GRAY
                    : src.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
            if (factor == 1.0 && src.getType() == type) {
                return src;
            }
            BufferedImage out = new BufferedImage(w, h, type);
            Graphics2D g = out.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(src, 0, 0, w, h, null);
            g.dispose();
            return out;
        }
    }
}

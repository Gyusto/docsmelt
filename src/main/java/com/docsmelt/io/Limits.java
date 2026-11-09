package com.docsmelt.io;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Iterator;

/**
 * Guards against "bomb" files: tiny uploads that would expand into enormous
 * images or renders and exhaust memory. Sizes are checked from headers,
 * before anything is decoded.
 */
public final class Limits {

    /** Largest image we decode: 100 megapixels (≈ 400 MB as ARGB). */
    public static final long MAX_IMAGE_PIXELS = 100_000_000L;
    /** Largest page render: 50 megapixels, whatever DPI was asked for. */
    public static final long MAX_RENDER_PIXELS = 50_000_000L;

    private Limits() {
    }

    /** Throws if the image file claims dimensions above {@link #MAX_IMAGE_PIXELS}. */
    public static void checkImage(Path file) {
        try (ImageInputStream in = ImageIO.createImageInputStream(file.toFile())) {
            check(in, file.getFileName().toString());
        } catch (IOException e) {
            // unreadable header: let the real decoder report it
        }
    }

    /** Same check for image bytes embedded in Office documents. */
    public static boolean imageFits(byte[] data) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            check(in, "embedded image");
            return true;
        } catch (ConversionException e) {
            return false;
        } catch (IOException e) {
            return true;
        }
    }

    private static void check(ImageInputStream in, String name) throws IOException {
        if (in == null) {
            return;
        }
        Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
        if (!readers.hasNext()) {
            return;
        }
        ImageReader reader = readers.next();
        try {
            reader.setInput(in, true, true);
            long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
            if (pixels > MAX_IMAGE_PIXELS) {
                throw new ConversionException(name + " is too large to process ("
                        + pixels / 1_000_000 + " megapixels; the limit is " + MAX_IMAGE_PIXELS / 1_000_000 + ")");
            }
        } finally {
            reader.dispose();
        }
    }

    /** True if an embedded image of this size is safe to decode. */
    public static boolean pixelsOk(long width, long height) {
        return width * height <= MAX_IMAGE_PIXELS;
    }

    /**
     * The DPI to render a page of {@code widthPt × heightPt} at: {@code wanted},
     * lowered if the result would exceed {@link #MAX_RENDER_PIXELS}.
     */
    public static float renderDpi(float widthPt, float heightPt, float wanted) {
        double pixelsAt72 = (double) widthPt * heightPt;
        double maxDpi = 72 * Math.sqrt(MAX_RENDER_PIXELS / Math.max(pixelsAt72, 1));
        return (float) Math.min(wanted, maxDpi);
    }

    /** Render scale (1 = 72 dpi) for a target width, capped the same way. */
    public static float renderScale(float widthPt, float heightPt, float wantedScale) {
        return renderDpi(widthPt, heightPt, wantedScale * 72f) / 72f;
    }

    /** Page count, or -1 if the file is encrypted (tools report that themselves). */
    public static int pdfPages(Path pdf) {
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            return doc.getNumberOfPages();
        } catch (InvalidPasswordException e) {
            return -1;
        } catch (IOException e) {
            throw new ConversionException("Could not read PDF " + pdf.getFileName() + ": " + e.getMessage(), e);
        }
    }
}

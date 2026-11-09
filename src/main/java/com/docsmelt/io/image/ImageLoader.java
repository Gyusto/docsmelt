package com.docsmelt.io.image;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/**
 * Reads images the way people see them. Phone cameras store photos unrotated
 * plus an EXIF "Orientation" tag; Java's ImageIO ignores that tag, so photos
 * come out sideways. This class reads the tag and applies it.
 */
public final class ImageLoader {

    private ImageLoader() {
    }

    /** @return the upright image, or null if the file isn't a readable image */
    public static BufferedImage read(Path file) throws IOException {
        BufferedImage img = ImageIO.read(file.toFile());
        if (img == null) {
            return null;
        }
        return applyOrientation(img, orientation(file));
    }

    /**
     * EXIF orientation 1–8 (1 = upright), or 1 when the file has no tag.
     * Only JPEGs carry it in practice; other formats return 1.
     */
    public static int orientation(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] head = in.readNBytes(128 * 1024); // EXIF lives in the first APP1 segment
            return exifOrientation(head);
        } catch (IOException | RuntimeException e) {
            return 1;
        }
    }

    static int exifOrientation(byte[] jpeg) {
        if (jpeg.length < 4 || (jpeg[0] & 0xFF) != 0xFF || (jpeg[1] & 0xFF) != 0xD8) {
            return 1; // not a JPEG
        }
        int pos = 2;
        while (pos + 4 <= jpeg.length) {
            if ((jpeg[pos] & 0xFF) != 0xFF) {
                return 1;
            }
            int marker = jpeg[pos + 1] & 0xFF;
            int length = ((jpeg[pos + 2] & 0xFF) << 8) | (jpeg[pos + 3] & 0xFF);
            if (marker == 0xDA || marker == 0xD9) {
                return 1; // image data starts: no EXIF found
            }
            int start = pos + 4;
            if (marker == 0xE1 && start + 6 <= jpeg.length
                    && jpeg[start] == 'E' && jpeg[start + 1] == 'x' && jpeg[start + 2] == 'i' && jpeg[start + 3] == 'f') {
                return tiffOrientation(jpeg, start + 6, Math.min(jpeg.length, pos + 2 + length));
            }
            pos += 2 + length;
        }
        return 1;
    }

    /** Reads tag 0x0112 from IFD0 of the TIFF structure inside EXIF. */
    private static int tiffOrientation(byte[] b, int tiff, int end) {
        if (tiff + 8 > end) {
            return 1;
        }
        ByteOrder order = b[tiff] == 'I' ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;
        ByteBuffer buf = ByteBuffer.wrap(b, 0, end).order(order);
        int ifd = tiff + buf.getInt(tiff + 4);
        if (ifd + 2 > end) {
            return 1;
        }
        int entries = buf.getShort(ifd) & 0xFFFF;
        for (int i = 0; i < entries; i++) {
            int entry = ifd + 2 + i * 12;
            if (entry + 12 > end) {
                return 1;
            }
            if ((buf.getShort(entry) & 0xFFFF) == 0x0112) {
                int value = buf.getShort(entry + 8) & 0xFFFF;
                return value >= 1 && value <= 8 ? value : 1;
            }
        }
        return 1;
    }

    /**
     * Rotates/flips according to an EXIF orientation value. Each case says
     * which stored pixel appears at display position (x, y).
     */
    public static BufferedImage applyOrientation(BufferedImage src, int orientation) {
        if (orientation <= 1 || orientation > 8) {
            return src;
        }
        int w = src.getWidth();
        int h = src.getHeight();
        boolean swap = orientation >= 5;
        int dw = swap ? h : w;
        int dh = swap ? w : h;
        int[] in = src.getRGB(0, 0, w, h, null, 0, w);
        int[] out = new int[dw * dh];
        for (int y = 0; y < dh; y++) {
            for (int x = 0; x < dw; x++) {
                int sx;
                int sy;
                switch (orientation) {
                    case 2 -> { sx = w - 1 - x; sy = y; }          // mirrored
                    case 3 -> { sx = w - 1 - x; sy = h - 1 - y; }  // upside down
                    case 4 -> { sx = x; sy = h - 1 - y; }          // flipped vertically
                    case 5 -> { sx = y; sy = x; }                  // transposed
                    case 6 -> { sx = y; sy = h - 1 - x; }          // needs 90° clockwise
                    case 7 -> { sx = w - 1 - y; sy = h - 1 - x; }  // transverse
                    default -> { sx = w - 1 - y; sy = x; }         // 8: needs 90° counter-clockwise
                }
                out[y * dw + x] = in[sy * w + sx];
            }
        }
        boolean alpha = src.getColorModel().hasAlpha();
        BufferedImage result = new BufferedImage(dw, dh, alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        result.setRGB(0, 0, dw, dh, out, 0, dw);
        return result;
    }
}

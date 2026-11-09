package com.docsmelt.pdf;

import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.util.Matrix;

/**
 * Converts between what a reader <em>sees</em> on a page and PDF user space.
 *
 * <p>A page with {@code /Rotate 90} is drawn rotated, so "bottom right as
 * displayed" is somewhere else in the page's own coordinates. Callers work in
 * display coordinates — origin bottom-left of the visible page, x right, y up
 * — and this class produces the matrix that puts upright content there.
 */
final class PageGeometry {

    private final float llx;
    private final float lly;
    private final float w; // crop box, unrotated
    private final float h;
    private final int rotation;

    PageGeometry(PDPage page) {
        PDRectangle box = page.getCropBox();
        this.llx = box.getLowerLeftX();
        this.lly = box.getLowerLeftY();
        this.w = box.getWidth();
        this.h = box.getHeight();
        this.rotation = Math.floorMod(page.getRotation(), 360);
    }

    /** Width of the page as displayed. */
    float width() {
        return rotation == 90 || rotation == 270 ? h : w;
    }

    /** Height of the page as displayed. */
    float height() {
        return rotation == 90 || rotation == 270 ? w : h;
    }

    /**
     * Converts a point in page space (relative to the crop box's lower-left
     * corner) to display coordinates {u, v}: origin bottom-left as shown.
     */
    float[] toDisplay(float x, float y) {
        return switch (rotation) {
            case 90 -> new float[]{y, w - x};
            case 180 -> new float[]{w - x, h - y};
            case 270 -> new float[]{h - y, x};
            default -> new float[]{x, y};
        };
    }

    /**
     * Matrix mapping a unit (or text) space to display coordinates
     * {@code (u, v)} with scale {@code (sx, sy)}, then into user space.
     * Use scale 1 for text and the image size for images.
     */
    Matrix place(float u, float v, float sx, float sy) {
        // Display → user space is an affine map x = a·u + c·v + e, y = b·u + d·v + f.
        float a, b, c, d, e, f;
        switch (rotation) {
            case 90 -> { a = 0; b = 1; c = -1; d = 0; e = llx + w; f = lly; }
            case 180 -> { a = -1; b = 0; c = 0; d = -1; e = llx + w; f = lly + h; }
            case 270 -> { a = 0; b = -1; c = 1; d = 0; e = llx; f = lly + h; }
            default -> { a = 1; b = 0; c = 0; d = 1; e = llx; f = lly; }
        }
        // Compose with "scale by (sx, sy), then move to (u, v)".
        return new Matrix(a * sx, b * sx, c * sy, d * sy, a * u + c * v + e, b * u + d * v + f);
    }
}

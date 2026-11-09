package com.docsmelt.model;

import java.awt.image.BufferedImage;
import java.util.Objects;

/** An embedded raster image. */
public record ImageBlock(BufferedImage image) implements Block {

    public ImageBlock {
        Objects.requireNonNull(image, "image");
    }

    public int width() {
        return image.getWidth();
    }

    public int height() {
        return image.getHeight();
    }
}

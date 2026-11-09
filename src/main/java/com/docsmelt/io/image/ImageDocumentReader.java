package com.docsmelt.io.image;

import com.docsmelt.io.ConversionException;
import com.docsmelt.io.DocumentReader;
import com.docsmelt.io.ReadOptions;
import com.docsmelt.model.Document;
import com.docsmelt.model.ImageBlock;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;

/** Reads any image ImageIO supports into a one-page document. */
public final class ImageDocumentReader implements DocumentReader {

    @Override
    public Document read(Path source, ReadOptions options) {
        try {
            BufferedImage image = com.docsmelt.io.image.ImageLoader.read(source);
            if (image == null) {
                throw new ConversionException("Not a readable image: " + source);
            }
            Document document = new Document();
            // The image is both the content and an exact snapshot, so image→image is lossless.
            document.newPage().add(new ImageBlock(image)).withSnapshot(image);
            return options.pages().select(document);
        } catch (IOException e) {
            throw new ConversionException("Could not read image " + source + ": " + e.getMessage(), e);
        }
    }
}

package com.filekit.io.image;

import com.filekit.io.ConversionException;
import com.filekit.io.DocumentReader;
import com.filekit.io.ReadOptions;
import com.filekit.model.Document;
import com.filekit.model.ImageBlock;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;

/** Reads any image ImageIO supports into a one-page document. */
public final class ImageDocumentReader implements DocumentReader {

    @Override
    public Document read(Path source, ReadOptions options) {
        try {
            BufferedImage image = ImageIO.read(source.toFile());
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

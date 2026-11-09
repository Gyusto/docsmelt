package com.docsmelt.io.image;

import com.docsmelt.format.Format;
import com.docsmelt.io.ConversionException;
import com.docsmelt.io.DocumentWriter;
import com.docsmelt.io.WriteOptions;
import com.docsmelt.model.Document;
import com.docsmelt.model.Page;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Writes a document as image(s). A one-page result goes to the target path;
 * multi-page results become {@code name-1.png}, {@code name-2.png}, ...
 */
public final class ImageDocumentWriter implements DocumentWriter {

    private final Format format;

    public ImageDocumentWriter(Format format) {
        if (!format.isImage()) {
            throw new IllegalArgumentException(format + " is not an image format");
        }
        this.format = format;
    }

    @Override
    public boolean wantsSnapshots() {
        return true;
    }

    @Override
    public List<Path> write(Document document, Path target, WriteOptions options) {
        PageRasterizer rasterizer = new PageRasterizer(options.dpi());
        List<BufferedImage> images = new ArrayList<>();
        for (Page page : document.pages()) {
            page.snapshot().ifPresentOrElse(images::add, () -> images.addAll(rasterizer.render(page)));
        }
        if (images.isEmpty()) {
            throw new ConversionException("Document has no content to write as an image");
        }

        List<Path> written = new ArrayList<>();
        for (int i = 0; i < images.size(); i++) {
            Path out = images.size() == 1 ? target : numbered(target, i + 1);
            writeImage(images.get(i), out, options.jpegQuality());
            written.add(out);
        }
        return written;
    }

    private void writeImage(BufferedImage image, Path out, float jpegQuality) {
        BufferedImage prepared = needsOpaqueRgb() ? toOpaqueRgb(image) : image;
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName(format.primaryExtension());
        if (!writers.hasNext()) {
            throw new ConversionException("No image writer available for " + format);
        }
        ImageWriter writer = writers.next();
        try {
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (format == Format.JPEG) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(jpegQuality);
            }
            // ImageOutputStream does not truncate an existing file, so remove it first.
            Files.deleteIfExists(out);
            try (ImageOutputStream ios = ImageIO.createImageOutputStream(out.toFile())) {
                writer.setOutput(ios);
                writer.write(null, new IIOImage(prepared, null, null), param);
            }
        } catch (IOException e) {
            throw new ConversionException("Could not write image " + out + ": " + e.getMessage(), e);
        } finally {
            writer.dispose();
        }
    }

    /** JPEG and BMP writers reject images with an alpha channel. */
    private boolean needsOpaqueRgb() {
        return format == Format.JPEG || format == Format.BMP;
    }

    private static BufferedImage toOpaqueRgb(BufferedImage src) {
        if (src.getType() == BufferedImage.TYPE_INT_RGB) {
            return src;
        }
        BufferedImage rgb = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, src.getWidth(), src.getHeight());
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return rgb;
    }

    private static Path numbered(Path target, int n) {
        String name = target.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String numberedName = name.substring(0, dot) + "-" + n + name.substring(dot);
        return target.resolveSibling(numberedName);
    }
}

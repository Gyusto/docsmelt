package com.docsmelt.web;

import com.docsmelt.format.Format;
import com.docsmelt.io.ConversionException;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Semaphore;

/**
 * POST /api/preview — one PDF → JSON page thumbnails for the Organize tool:
 * {@code {"pages":[{"src":"data:image/jpeg;base64,...","rotation":0}, ...]}}.
 * Thumbnails are rendered without the page's own rotation; the UI applies
 * {@code rotation} plus the user's changes with CSS.
 */
final class PreviewHandler extends ApiHandler {

    private static final int THUMB_WIDTH = 180;
    static final int MAX_PAGES = 300;

    PreviewHandler(ServerConfig config, Semaphore slots) {
        super(config, slots);
    }

    @Override
    protected int maxFiles() {
        return 1;
    }

    @Override
    protected Reply process(Context ctx, List<Path> inputs, Workspace workspace) {
        Path source = inputs.getFirst();
        if (Format.fromPath(source) != Format.PDF) {
            throw new BadRequestResponse("Please choose a PDF file");
        }
        try (PDDocument pdf = Loader.loadPDF(source.toFile())) {
            int count = pdf.getNumberOfPages();
            if (count > MAX_PAGES) {
                throw new BadRequestResponse("This PDF has " + count + " pages; previews are limited to " + MAX_PAGES);
            }
            PDFRenderer renderer = new PDFRenderer(pdf);
            StringBuilder json = new StringBuilder("{\"pages\":[");
            for (int i = 0; i < count; i++) {
                int rotation = pdf.getPage(i).getRotation();
                pdf.getPage(i).setRotation(0); // render upright; the browser rotates it
                PDRectangle box = pdf.getPage(i).getCropBox();
                BufferedImage thumb = renderer.renderImage(i, THUMB_WIDTH / box.getWidth(), ImageType.RGB);
                json.append(i == 0 ? "" : ",")
                        .append("{\"src\":\"data:image/jpeg;base64,").append(jpegBase64(thumb))
                        .append("\",\"rotation\":").append(Math.floorMod(rotation, 360)).append('}');
            }
            return new Reply.Json(json.append("]}").toString());
        } catch (IOException e) {
            throw new ConversionException("Could not read PDF: " + e.getMessage(), e);
        }
    }

    private static String jpegBase64(BufferedImage img) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpeg", out);
        return Base64.getEncoder().encodeToString(out.toByteArray());
    }
}

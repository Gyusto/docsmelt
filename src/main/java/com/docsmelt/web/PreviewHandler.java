package com.docsmelt.web;

import com.docsmelt.io.Limits;
import com.docsmelt.format.Format;
import com.docsmelt.io.ConversionException;
import io.javalin.http.BadRequestResponse;
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

/**
 * POST /api/preview — one PDF → JSON page thumbnails for the Organize tool
 * (or, with {@code page}=N and {@code width}, one larger page as displayed, for Sign):
 * {@code {"pages":[{"src":"data:image/jpeg;base64,...","rotation":0}, ...]}}.
 * Thumbnails are rendered without the page's own rotation; the UI applies
 * {@code rotation} plus the user's changes with CSS.
 */
final class PreviewHandler extends ApiHandler {

    private static final int THUMB_WIDTH = 180;
    static final int MAX_PAGES = 300;

    private final com.docsmelt.core.Converter converter;

    PreviewHandler(ServerConfig config, JobManager jobs, com.docsmelt.core.Converter converter) {
        super(config, jobs);
        this.converter = converter;
    }

    @Override
    protected int maxFiles() {
        return 1;
    }

    @Override
    protected Reply process(Params ctx, List<Path> inputs, Workspace workspace) {
        Path source = inputs.getFirst();
        Format format = Format.fromPath(source);
        if (format != Format.PDF) {
            if (ctx.formParam("page") == null) {
                throw new BadRequestResponse("Please choose a PDF file"); // page thumbnails (Organize) are PDF-only
            }
            if (!converter.registry().readableFormats().contains(format)) {
                throw new BadRequestResponse("Can't preview ." + format.primaryExtension() + " files");
            }
            // Word, Excel, PowerPoint, text, images: preview what they'd look like as PDF.
            Path asPdf = workspace.out().resolve("preview.pdf");
            converter.convert(source, asPdf);
            source = asPdf;
        }
        try (PDDocument pdf = Loader.loadPDF(source.toFile())) {
            int count = pdf.getNumberOfPages();
            PDFRenderer renderer = new PDFRenderer(pdf);

            // Single page as displayed (rotation applied), larger: used to place a signature.
            if (ctx.formParam("page") != null) {
                int page = intParam(ctx, "page", 0, 0, count);
                int index = (page == 0 ? count : page) - 1;
                int width = intParam(ctx, "width", 700, 100, 1200);
                PDRectangle box = pdf.getPage(index).getCropBox();
                boolean sideways = Math.floorMod(pdf.getPage(index).getRotation(), 180) == 90;
                float pageWidth = sideways ? box.getHeight() : box.getWidth();
                float scale = Limits.renderScale(box.getWidth(), box.getHeight(), width / pageWidth);
                BufferedImage img = renderer.renderImage(index, scale, ImageType.RGB);
                return new Reply.Json("{\"count\":" + count + ",\"page\":" + (index + 1)
                        + ",\"pages\":[{\"src\":\"data:image/jpeg;base64," + jpegBase64(img) + "\",\"rotation\":0}]}");
            }

            if (count > MAX_PAGES) {
                throw new BadRequestResponse("This PDF has " + count + " pages; previews are limited to " + MAX_PAGES);
            }
            StringBuilder json = new StringBuilder("{\"count\":" + count + ",\"pages\":[");
            for (int i = 0; i < count; i++) {
                int rotation = pdf.getPage(i).getRotation();
                pdf.getPage(i).setRotation(0); // render upright; the browser rotates it
                PDRectangle box = pdf.getPage(i).getCropBox();
                float scale = Limits.renderScale(box.getWidth(), box.getHeight(), THUMB_WIDTH / box.getWidth());
                BufferedImage thumb = renderer.renderImage(i, scale, ImageType.RGB);
                json.append(i == 0 ? "" : ",")
                        .append("{\"src\":\"data:image/jpeg;base64,").append(jpegBase64(thumb))
                        .append("\",\"rotation\":").append(Math.floorMod(rotation, 360)).append('}');
            }
            return new Reply.Json(json.append("]}").toString());
        } catch (org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException e) {
            throw new ConversionException("This PDF is password-protected; unlock it first", e);
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

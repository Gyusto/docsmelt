package com.docsmelt.web;

import com.docsmelt.format.Format;
import com.docsmelt.io.PageRange;
import com.docsmelt.pdf.PdfImageExtractor;
import com.docsmelt.pdf.PdfPageSizer;
import com.docsmelt.pdf.PdfWatermarker;
import io.javalin.http.BadRequestResponse;

import java.awt.Color;
import java.nio.file.Path;
import java.util.List;

/**
 * The newer single-PDF tools:
 * <pre>
 *   POST /api/watermark       files: PDF (+ optional image), text?, color?, opacity?, angle?, size?, layout?, pages?
 *   POST /api/extract-images  files: PDF, pages?
 *   POST /api/crop            files: PDF, mode=auto|margin|resize, margin? (mm), paper?
 * </pre>
 */
final class PageToolsHandler extends ApiHandler {

    enum Tool { WATERMARK, EXTRACT_IMAGES, CROP }

    private final Tool tool;

    PageToolsHandler(ServerConfig config, JobManager jobs, Tool tool) {
        super(config, jobs);
        this.tool = tool;
    }

    @Override
    protected int maxFiles() {
        return tool == Tool.WATERMARK ? 2 : 1;
    }

    @Override
    protected Reply process(Params ctx, List<Path> inputs, Workspace workspace) {
        Path pdf = inputs.stream().filter(p -> Format.fromPath(p) == Format.PDF).findFirst()
                .orElseThrow(() -> new BadRequestResponse("Please choose a PDF file"));
        String stem = stem(pdf);
        return switch (tool) {
            case WATERMARK -> {
                Path image = inputs.stream().filter(p -> p != pdf).findFirst().orElse(null);
                String text = image == null ? ctx.formParam("text") : null;
                PdfWatermarker.Options options = new PdfWatermarker.Options(text, image,
                        color(ctx.formParam("color")),
                        number(ctx, "opacity", 0.25f), number(ctx, "angle", 45f), number(ctx, "size", 0f),
                        PdfWatermarker.Layout.parse(ImagesHandler.orDefault(ctx.formParam("layout"), "center")),
                        PageRange.parse(ctx.formParam("pages")));
                Path out = workspace.out().resolve(stem + "-watermarked.pdf");
                new PdfWatermarker().apply(pdf, out, options);
                yield new Reply.Files(List.of(out), "watermarked.zip");
            }
            case EXTRACT_IMAGES -> {
                List<Path> images = new PdfImageExtractor().extract(pdf, workspace.out(), PageRange.parse(ctx.formParam("pages")));
                yield new Reply.Files(images, stem + "-images.zip");
            }
            case CROP -> {
                Path out = workspace.out().resolve(stem + "-cropped.pdf");
                PdfPageSizer sizer = new PdfPageSizer();
                switch (ImagesHandler.orDefault(ctx.formParam("mode"), "auto")) {
                    case "auto" -> sizer.crop(pdf, out, -1);
                    case "margin" -> sizer.crop(pdf, out, Math.max(0, number(ctx, "margin", 10f)));
                    case "resize" -> {
                        out = workspace.out().resolve(stem + "-resized.pdf");
                        sizer.resize(pdf, out, PdfPageSizer.Paper.parse(ImagesHandler.orDefault(ctx.formParam("paper"), "a4")));
                    }
                    default -> throw new BadRequestResponse("mode must be auto, margin or resize");
                }
                yield new Reply.Files(List.of(out), "cropped.zip");
            }
        };
    }

    private static float number(Params ctx, String name, float fallback) {
        String raw = ctx.formParam(name);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Float.parseFloat(raw.strip());
        } catch (NumberFormatException e) {
            throw new BadRequestResponse(name + " must be a number");
        }
    }

    private static Color color(String hex) {
        if (hex == null || hex.isBlank()) {
            return new Color(0xC73A2A);
        }
        String h = hex.strip().replaceFirst("^#", "");
        if (!h.matches("[0-9a-fA-F]{6}")) {
            throw new BadRequestResponse("Colour must look like #c73a2a");
        }
        return new Color(Integer.parseInt(h, 16));
    }
}

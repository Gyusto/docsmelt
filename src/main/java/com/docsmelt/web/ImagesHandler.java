package com.docsmelt.web;

import com.docsmelt.pdf.ImagesToPdf;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;

/** POST /api/images-to-pdf — files[] (images, in order), pageSize=a4|letter|fit, margin=none|small|normal */
final class ImagesHandler extends ApiHandler {

    private static final Map<String, Float> MARGINS = Map.of("none", 0f, "small", 18f, "normal", 36f);

    ImagesHandler(ServerConfig config, Semaphore slots) {
        super(config, slots);
    }

    @Override
    protected Reply process(Context ctx, List<Path> inputs, Workspace workspace) {
        ImagesToPdf.PageSize size = ImagesToPdf.PageSize.parse(orDefault(ctx.formParam("pageSize"), "a4"));
        Float margin = MARGINS.get(orDefault(ctx.formParam("margin"), "small"));
        if (margin == null) {
            throw new BadRequestResponse("margin must be none, small or normal");
        }
        Path out = workspace.out().resolve(inputs.size() == 1 ? stem(inputs.getFirst()) + ".pdf" : "images.pdf");
        new ImagesToPdf().create(inputs, out, size, margin);
        return new Reply.Files(List.of(out), "images.zip");
    }

    static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }
}

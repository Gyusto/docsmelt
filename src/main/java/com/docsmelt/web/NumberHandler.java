package com.docsmelt.web;

import com.docsmelt.io.PageRange;
import com.docsmelt.pdf.PageNumberer;
import io.javalin.http.Context;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Semaphore;

/** POST /api/number — one PDF, position?, format?, start?, pages?, size? */
final class NumberHandler extends ApiHandler {

    NumberHandler(ServerConfig config, Semaphore slots) {
        super(config, slots);
    }

    @Override
    protected int maxFiles() {
        return 1;
    }

    @Override
    protected Reply process(Context ctx, List<Path> inputs, Workspace workspace) {
        PageNumberer.Options options = new PageNumberer.Options(
                PageNumberer.Position.parse(ImagesHandler.orDefault(ctx.formParam("position"), "bottom-center")),
                ImagesHandler.orDefault(ctx.formParam("format"), "{n}"),
                intParam(ctx, "start", 1, 0, 100_000),
                PageRange.parse(ctx.formParam("pages")),
                intParam(ctx, "size", 10, 6, 36));
        Path source = inputs.getFirst();
        Path out = workspace.out().resolve(stem(source) + "-numbered.pdf");
        new PageNumberer().apply(source, out, options);
        return new Reply.Files(List.of(out), "numbered.zip");
    }
}

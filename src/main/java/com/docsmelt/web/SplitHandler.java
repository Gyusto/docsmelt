package com.docsmelt.web;

import com.docsmelt.pdf.PdfSplitter;
import com.docsmelt.pdf.SplitStrategy;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Semaphore;

/** POST /api/split — one PDF, plus either {@code every=N} or {@code ranges=1-3,4-}. */
final class SplitHandler extends ApiHandler {

    private final PdfSplitter splitter;

    SplitHandler(ServerConfig config, Semaphore slots, PdfSplitter splitter) {
        super(config, slots);
        this.splitter = splitter;
    }

    @Override
    protected int maxFiles() {
        return 1;
    }

    @Override
    protected Reply process(Context ctx, List<Path> inputs, Workspace workspace) {
        return new Reply.Files(splitter.split(inputs.getFirst(), strategy(ctx), workspace.out()),
                stem(inputs.getFirst()) + "-split.zip");
    }

    private static SplitStrategy strategy(Context ctx) {
        String ranges = ctx.formParam("ranges");
        try {
            if (ranges != null && !ranges.isBlank()) {
                return SplitStrategy.ByRanges.parse(ranges);
            }
            return new SplitStrategy.EveryNPages(intParam(ctx, "every", 1, 1, 10_000));
        } catch (IllegalArgumentException e) {
            throw new BadRequestResponse(e.getMessage());
        }
    }
}

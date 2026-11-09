package com.docsmelt.web;

import com.docsmelt.pdf.PdfSplitter;
import com.docsmelt.pdf.SplitStrategy;
import io.javalin.http.BadRequestResponse;

import java.nio.file.Path;
import java.util.List;

/** POST /api/split — one PDF, plus either {@code every=N} or {@code ranges=1-3,4-}. */
final class SplitHandler extends ApiHandler {

    private final PdfSplitter splitter;

    SplitHandler(ServerConfig config, JobManager jobs, PdfSplitter splitter) {
        super(config, jobs);
        this.splitter = splitter;
    }

    @Override
    protected int maxFiles() {
        return 1;
    }

    @Override
    protected Reply process(Params ctx, List<Path> inputs, Workspace workspace) {
        return new Reply.Files(splitter.split(inputs.getFirst(), strategy(ctx), workspace.out()),
                stem(inputs.getFirst()) + "-split.zip");
    }

    private static SplitStrategy strategy(Params ctx) {
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

package com.docsmelt.web;

import com.docsmelt.core.ConversionOptions;
import com.docsmelt.pdf.PdfMerger;

import java.nio.file.Path;
import java.util.List;

/** POST /api/merge — files[] in the order they should appear. */
final class MergeHandler extends ApiHandler {

    private final PdfMerger merger;

    MergeHandler(ServerConfig config, JobManager jobs, PdfMerger merger) {
        super(config, jobs);
        this.merger = merger;
    }

    @Override
    protected int minFiles() {
        return 2;
    }

    @Override
    protected Reply process(Params ctx, List<Path> inputs, Workspace workspace) {
        Path out = workspace.out().resolve("merged.pdf");
        merger.merge(inputs, out, ConversionOptions.DEFAULT);
        return new Reply.Files(List.of(out), "merged.zip");
    }
}

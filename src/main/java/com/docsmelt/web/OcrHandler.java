package com.docsmelt.web;

import com.docsmelt.io.PageRange;
import com.docsmelt.ocr.OcrService;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Semaphore;

/** POST /api/ocr — one PDF or image, lang=eng[+swa], output=pdf|txt|docx, pages? */
final class OcrHandler extends ApiHandler {

    /** OCR takes seconds per page; keep web requests reasonable. */
    static final int MAX_PAGES = 30;
    private static final Set<String> OUTPUTS = Set.of("pdf", "txt", "docx");

    private final OcrService ocr;

    OcrHandler(ServerConfig config, Semaphore slots, OcrService ocr) {
        super(config, slots);
        this.ocr = ocr;
    }

    @Override
    protected int maxFiles() {
        return 1;
    }

    @Override
    protected Reply process(Context ctx, List<Path> inputs, Workspace workspace) {
        String output = ImagesHandler.orDefault(ctx.formParam("output"), "pdf");
        if (!OUTPUTS.contains(output)) {
            throw new BadRequestResponse("output must be pdf, txt or docx");
        }
        OcrService.Options options = new OcrService.Options(
                ImagesHandler.orDefault(ctx.formParam("lang"), "eng"), 300,
                PageRange.parse(ctx.formParam("pages")), MAX_PAGES);
        Path source = inputs.getFirst();
        Path out = workspace.out().resolve(stem(source) + (output.equals("pdf") ? "-searchable.pdf" : "." + output));
        ocr.recognize(source, out, options);
        return new Reply.Files(List.of(out), "ocr.zip");
    }
}

package com.docsmelt.web;

import com.docsmelt.io.ConversionException;
import com.docsmelt.pdf.PdfCompressor;
import io.javalin.http.Context;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;

/** POST /api/compress — files[] (PDFs), level=low|medium|high */
final class CompressHandler extends ApiHandler {

    CompressHandler(ServerConfig config, Semaphore slots) {
        super(config, slots);
    }

    @Override
    protected Reply process(Context ctx, List<Path> inputs, Workspace workspace) {
        PdfCompressor.Level level = PdfCompressor.Level.parse(ImagesHandler.orDefault(ctx.formParam("level"), "medium"));
        PdfCompressor compressor = new PdfCompressor();
        List<Path> outputs = new ArrayList<>();
        for (int i = 0; i < inputs.size(); i++) {
            Path input = inputs.get(i);
            Path out = workspace.out().resolve(String.valueOf(i)).resolve(stem(input) + "-compressed.pdf");
            try {
                compressor.compress(input, out, level);
            } catch (ConversionException e) {
                throw new ConversionException(input.getFileName() + ": " + e.getMessage(), e);
            }
            outputs.add(out);
        }
        return new Reply.Files(outputs, "compressed.zip");
    }
}

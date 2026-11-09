package com.docsmelt.web;

import com.docsmelt.core.ConversionOptions;
import com.docsmelt.core.Converter;
import com.docsmelt.format.Format;
import com.docsmelt.io.ConversionException;
import com.docsmelt.io.PageRange;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Semaphore;

/** POST /api/convert — files[], to, [pages], [dpi], [quality] */
final class ConvertHandler extends ApiHandler {

    private final Converter converter;

    ConvertHandler(ServerConfig config, Semaphore slots, Converter converter) {
        super(config, slots);
        this.converter = converter;
    }

    @Override
    protected List<Path> process(Context ctx, List<Path> inputs, Workspace workspace) {
        Format target = targetFormat(ctx.formParam("to"));
        ConversionOptions options = options(ctx);

        List<Path> outputs = new ArrayList<>();
        for (int i = 0; i < inputs.size(); i++) {
            Path input = inputs.get(i);
            // Separate folder per input so "a.docx" and "a.txt" can both become "a.pdf".
            Path out = workspace.out().resolve(String.valueOf(i)).resolve(stem(input) + "." + target.primaryExtension());
            try {
                outputs.addAll(converter.convert(input, out, options));
            } catch (ConversionException e) {
                throw new ConversionException(input.getFileName() + ": " + e.getMessage(), e);
            }
        }
        return outputs;
    }

    @Override
    protected String zipName(List<Path> inputs) {
        return inputs.size() == 1 ? stem(inputs.getFirst()) + ".zip" : "converted.zip";
    }

    private Format targetFormat(String to) {
        if (to == null || to.isBlank()) {
            throw new BadRequestResponse("Please choose an output format");
        }
        Format format = Format.fromPath(Path.of("x." + to.strip().toLowerCase(Locale.ROOT)));
        if (!converter.registry().writableFormats().contains(format)) {
            throw new BadRequestResponse("Cannot write " + to);
        }
        return format;
    }

    private static ConversionOptions options(Context ctx) {
        int dpi = intParam(ctx, "dpi", 150, 36, 600);
        int quality = intParam(ctx, "quality", 90, 1, 100);
        PageRange pages;
        try {
            pages = PageRange.parse(ctx.formParam("pages"));
        } catch (IllegalArgumentException e) {
            throw new BadRequestResponse(e.getMessage());
        }
        return new ConversionOptions(dpi, pages, quality / 100f, List.of());
    }
}

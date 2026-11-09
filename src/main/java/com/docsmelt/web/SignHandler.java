package com.docsmelt.web;

import com.docsmelt.format.Format;
import com.docsmelt.pdf.PdfSigner;
import io.javalin.http.BadRequestResponse;

import java.nio.file.Path;
import java.util.List;

/**
 * POST /api/sign — files: the PDF and the signature image (any order),
 * page (0 = last), x, y, width as fractions of the displayed page.
 */
final class SignHandler extends ApiHandler {

    SignHandler(ServerConfig config, JobManager jobs) {
        super(config, jobs);
    }

    @Override
    protected int minFiles() {
        return 2;
    }

    @Override
    protected int maxFiles() {
        return 2;
    }

    @Override
    protected Reply process(Params ctx, List<Path> inputs, Workspace workspace) {
        Path pdf = inputs.stream().filter(p -> Format.fromPath(p) == Format.PDF).findFirst()
                .orElseThrow(() -> new BadRequestResponse("Please include the PDF to sign"));
        Path signature = inputs.stream().filter(p -> p != pdf).findFirst().orElseThrow();
        PdfSigner.Placement placement = new PdfSigner.Placement(
                intParam(ctx, "page", 0, 0, 100_000),
                fraction(ctx, "x"), fraction(ctx, "y"), fraction(ctx, "width"));
        Path out = workspace.out().resolve(stem(pdf) + "-signed.pdf");
        new PdfSigner().sign(pdf, signature, out, placement);
        return new Reply.Files(List.of(out), "signed.zip");
    }

    private static float fraction(Params ctx, String name) {
        String raw = ctx.formParam(name);
        if (raw == null || raw.isBlank()) {
            throw new BadRequestResponse("Missing " + name);
        }
        try {
            return Float.parseFloat(raw.strip());
        } catch (NumberFormatException e) {
            throw new BadRequestResponse(name + " must be a number between 0 and 1");
        }
    }
}

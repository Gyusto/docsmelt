package com.docsmelt.web;

import com.docsmelt.pdf.PdfSecurity;
import io.javalin.http.BadRequestResponse;

import java.nio.file.Path;
import java.util.List;

/**
 * POST /api/protect — one PDF, password, allowPrint?, allowCopy?, allowEdit? ("true"/"false")
 * POST /api/unlock  — one PDF, password
 * Passwords are never logged.
 */
final class SecurityHandler extends ApiHandler {

    enum Mode { PROTECT, UNLOCK }

    private final Mode mode;

    SecurityHandler(ServerConfig config, JobManager jobs, Mode mode) {
        super(config, jobs);
        this.mode = mode;
    }

    @Override
    protected int maxFiles() {
        return 1;
    }

    @Override
    protected Reply process(Params ctx, List<Path> inputs, Workspace workspace) {
        String password = ctx.formParam("password");
        if (password == null || password.isEmpty()) {
            throw new BadRequestResponse("Please enter a password");
        }
        Path source = inputs.getFirst();
        PdfSecurity security = new PdfSecurity();
        Path out;
        if (mode == Mode.PROTECT) {
            out = workspace.out().resolve(stem(source) + "-protected.pdf");
            security.protect(source, out, password, null, new PdfSecurity.Permissions(
                    flag(ctx, "allowPrint"), flag(ctx, "allowCopy"), flag(ctx, "allowEdit")));
        } else {
            out = workspace.out().resolve(stem(source) + "-unlocked.pdf");
            security.unlock(source, out, password);
        }
        return new Reply.Files(List.of(out), "result.zip");
    }

    /** Missing = allowed, so a bare request keeps the usual permissions. */
    private static boolean flag(Params ctx, String name) {
        return !"false".equalsIgnoreCase(ctx.formParam(name));
    }
}

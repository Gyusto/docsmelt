package com.docsmelt.web;

import com.docsmelt.pdf.PageEditPlan;
import com.docsmelt.pdf.PdfPageEditor;
import io.javalin.http.BadRequestResponse;

import java.nio.file.Path;
import java.util.List;

/**
 * POST /api/organize — one PDF, {@code order} ("3,1,2": original page numbers
 * to keep, in output order) and {@code rotate} ("1:90,3:270": extra clockwise
 * rotation per original page).
 */
final class OrganizeHandler extends ApiHandler {

    OrganizeHandler(ServerConfig config, JobManager jobs) {
        super(config, jobs);
    }

    @Override
    protected int maxFiles() {
        return 1;
    }

    @Override
    protected Reply process(Params ctx, List<Path> inputs, Workspace workspace) {
        String order = ctx.formParam("order");
        if (order == null || order.isBlank()) {
            throw new BadRequestResponse("Missing page order");
        }
        PageEditPlan plan = new PageEditPlan()
                .order(PageEditPlan.parseOrder(order))
                .rotatePerPage(ImagesHandler.orDefault(ctx.formParam("rotate"), ""));
        Path source = inputs.getFirst();
        Path out = workspace.out().resolve(stem(source) + "-organized.pdf");
        new PdfPageEditor().apply(source, plan, out);
        return new Reply.Files(List.of(out), "organized.zip");
    }
}

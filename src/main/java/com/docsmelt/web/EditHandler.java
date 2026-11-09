package com.docsmelt.web;

import com.docsmelt.format.Format;
import com.docsmelt.pdf.PdfAConverter;
import com.docsmelt.pdf.PdfAnnotator;
import com.docsmelt.pdf.PdfFormFiller;
import com.docsmelt.pdf.PdfRedactor;
import com.docsmelt.pdf.PdfTextFinder;
import io.javalin.http.BadRequestResponse;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * The editing tools, all on one PDF:
 * <pre>
 *   POST /api/find-text    q                          → JSON {"matches":[{"page":1,"x":…,"y":…,"w":…,"h":…}]}
 *   POST /api/redact       box=page:x,y,w,h (repeat), removeMetadata?
 *   POST /api/form-fields                             → JSON {"fields":[…]}
 *   POST /api/fill-form    field.&lt;name&gt;=value (repeat), flatten?
 *   POST /api/annotate     mark=… (repeat, see PdfAnnotator.Mark)
 *   POST /api/pdfa
 * </pre>
 */
final class EditHandler extends ApiHandler {

    enum Tool { FIND_TEXT, REDACT, FORM_FIELDS, FILL_FORM, ANNOTATE, PDFA }

    private static final int MAX_ITEMS = 5_000;

    private final Tool tool;
    private final PdfAConverter pdfa;

    EditHandler(ServerConfig config, JobManager jobs, Tool tool, PdfAConverter pdfa) {
        super(config, jobs);
        this.tool = tool;
        this.pdfa = pdfa;
    }

    @Override
    protected int maxFiles() {
        return 1;
    }

    @Override
    protected int requestsPerMinute(ServerConfig config) {
        return tool == Tool.FIND_TEXT || tool == Tool.FORM_FIELDS ? lightweight(config) : config.requestsPerMinute();
    }

    @Override
    protected Reply process(Params ctx, List<Path> inputs, Workspace workspace) {
        Path pdf = inputs.getFirst();
        if (Format.fromPath(pdf) != Format.PDF) {
            throw new BadRequestResponse("Please choose a PDF file");
        }
        String stem = stem(pdf);
        return switch (tool) {
            case FIND_TEXT -> {
                List<PdfRedactor.Box> boxes = new PdfTextFinder().find(pdf, ctx.formParam("q"));
                yield new Reply.Json(boxes.stream()
                        .map(b -> String.format(Locale.ROOT, "{\"page\":%d,\"x\":%.5f,\"y\":%.5f,\"w\":%.5f,\"h\":%.5f}",
                                b.page(), b.x(), b.y(), b.w(), b.h()))
                        .collect(Collectors.joining(",", "{\"matches\":[", "]}")));
            }
            case REDACT -> {
                List<String> specs = limited(ctx.formParams("box"));
                List<PdfRedactor.Box> boxes = specs.stream().map(PdfRedactor.Box::parse).toList();
                Path out = workspace.out().resolve(stem + "-redacted.pdf");
                new PdfRedactor().redact(pdf, out, boxes, !"false".equals(ctx.formParam("removeMetadata")));
                yield new Reply.Files(List.of(out), "redacted.zip");
            }
            case FORM_FIELDS -> new Reply.Json(new PdfFormFiller().fields(pdf).stream()
                    .map(EditHandler::fieldJson)
                    .collect(Collectors.joining(",", "{\"fields\":[", "]}")));
            case FILL_FORM -> {
                Path out = workspace.out().resolve(stem + "-filled.pdf");
                new PdfFormFiller().fill(pdf, out, ctx.withPrefix("field."), "true".equals(ctx.formParam("flatten")));
                yield new Reply.Files(List.of(out), "filled.zip");
            }
            case ANNOTATE -> {
                List<PdfAnnotator.Mark> marks = limited(ctx.formParams("mark")).stream().map(PdfAnnotator.Mark::parse).toList();
                Path out = workspace.out().resolve(stem + "-edited.pdf");
                new PdfAnnotator().apply(pdf, out, marks);
                yield new Reply.Files(List.of(out), "edited.zip");
            }
            case PDFA -> {
                Path out = workspace.out().resolve(stem + "-pdfa.pdf");
                pdfa.convert(pdf, out);
                yield new Reply.Files(List.of(out), "pdfa.zip");
            }
        };
    }

    private static List<String> limited(List<String> items) {
        if (items.size() > MAX_ITEMS) {
            throw new BadRequestResponse("Too many items (max " + MAX_ITEMS + ")");
        }
        return items;
    }

    private static String fieldJson(PdfFormFiller.Field f) {
        String options = f.options().stream().map(o -> "\"" + WebServer.escape(o) + "\"")
                .collect(Collectors.joining(",", "[", "]"));
        return "{\"name\":\"" + WebServer.escape(f.name()) + "\",\"label\":\"" + WebServer.escape(f.label())
                + "\",\"type\":\"" + f.type().name().toLowerCase(Locale.ROOT) + "\",\"value\":\"" + WebServer.escape(f.value())
                + "\",\"options\":" + options + ",\"multiline\":" + f.multiline() + ",\"readOnly\":" + f.readOnly()
                + ",\"required\":" + f.required() + ",\"maxLength\":" + f.maxLength() + "}";
    }
}

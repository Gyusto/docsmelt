package com.docsmelt.web;

import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.http.HttpStatus;
import io.javalin.http.ServiceUnavailableResponse;
import io.javalin.http.UploadedFile;
import io.javalin.http.util.NaiveRateLimit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Template method for the file endpoints: rate-limit → validate uploads →
 * save them into a {@link Workspace} → {@link #process} → send the result
 * (one file directly, several as a zip) → delete everything.
 */
abstract class ApiHandler implements Handler {

    private static final Logger LOG = LoggerFactory.getLogger(ApiHandler.class);
    private static final Map<String, String> CONTENT_TYPES = Map.of(
            "pdf", "application/pdf",
            "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "txt", "text/plain; charset=utf-8",
            "png", "image/png",
            "jpg", "image/jpeg",
            "bmp", "image/bmp",
            "gif", "image/gif",
            "xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "zip", "application/zip");

    private final ServerConfig config;
    private final JobManager jobs;

    ApiHandler(ServerConfig config, JobManager jobs) {
        this.config = config;
        this.jobs = jobs;
    }

    /** What an endpoint sends back. */
    sealed interface Reply {
        /** Files inside the workspace; one is sent as-is, several as {@code zipName}. */
        record Files(List<Path> files, String zipName) implements Reply {
        }

        record Json(String body) implements Reply {
        }
    }

    /** The actual work. Files returned must live inside {@code workspace.out()}. */
    protected abstract Reply process(Params ctx, List<Path> inputs, Workspace workspace);

    protected int minFiles() {
        return 1;
    }

    protected int maxFiles() {
        return config.maxFiles();
    }

    /**
     * Saves the uploads, then runs {@link #process} as a background job.
     * With form field {@code async=true} the response is 202 with the job id
     * (poll {@code /api/jobs/{id}}); otherwise this waits and sends the result.
     */
    @Override
    public final void handle(Context ctx) throws Exception {
        NaiveRateLimit.requestPerTimeUnit(ctx, config.requestsPerMinute(), TimeUnit.MINUTES);
        List<UploadedFile> uploads = ctx.uploadedFiles("files");
        validate(uploads);
        Params params = Params.of(ctx); // the job may outlive this request

        Workspace workspace = new Workspace();
        List<Path> inputs = new ArrayList<>();
        try {
            for (UploadedFile upload : uploads) {
                inputs.add(workspace.save(upload));
            }
            checkPageCounts(inputs);
        } catch (RuntimeException | IOException e) {
            workspace.close();
            throw e;
        }

        long started = System.nanoTime();
        String path = ctx.path();
        Job job = jobs.submit(workspace, () -> {
            Job.Result result = toResult(process(params, inputs, workspace), workspace);
            LOG.info("{} {} file(s) in {} ms", path, inputs.size(), TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            return result;
        });

        if ("true".equals(params.formParam("async"))) {
            ctx.status(HttpStatus.ACCEPTED).contentType("application/json").result("{\"id\":\"" + job.id + "\"}");
            return;
        }
        try {
            send(ctx, jobs.await(job));
        } finally {
            jobs.forget(job);
        }
    }

    /** Refuses PDFs with more pages than the server allows, before any work starts. */
    private void checkPageCounts(List<Path> inputs) {
        for (Path input : inputs) {
            if (input.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf")) {
                int pages = com.docsmelt.io.Limits.pdfPages(input);
                if (pages > config.maxPages()) {
                    throw new BadRequestResponse(input.getFileName() + " has " + pages
                            + " pages; the limit here is " + config.maxPages());
                }
            }
        }
    }

    private void validate(List<UploadedFile> uploads) {
        if (uploads.size() < minFiles()) {
            throw new BadRequestResponse(minFiles() == 1 ? "Please choose a file"
                    : "Please choose at least " + minFiles() + " files");
        }
        if (uploads.size() > maxFiles()) {
            throw new BadRequestResponse("Too many files (max " + maxFiles() + ")");
        }
        for (UploadedFile upload : uploads) {
            if (upload.size() > config.maxUploadBytes()) {
                throw new BadRequestResponse(upload.filename() + " is larger than " + config.maxUploadMb() + " MB");
            }
        }
    }

    /** Turns a reply into one downloadable file (several files are zipped) or a JSON body. */
    private static Job.Result toResult(Reply reply, Workspace workspace) throws IOException {
        return switch (reply) {
            case Reply.Json j -> Job.Result.json(j.body());
            case Reply.Files f when f.files().size() == 1 -> {
                Path file = f.files().getFirst();
                yield new Job.Result(file, file.getFileName().toString(), contentType(file.getFileName().toString()), null);
            }
            case Reply.Files f -> {
                Path zipFile = workspace.out().resolveSibling("result.zip");
                try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(zipFile))) {
                    Set<String> used = new HashSet<>();
                    for (Path file : f.files()) {
                        zip.putNextEntry(new ZipEntry(unique(file.getFileName().toString(), used)));
                        Files.copy(file, zip);
                        zip.closeEntry();
                    }
                }
                yield new Job.Result(zipFile, f.zipName(), contentType(f.zipName()), null);
            }
        };
    }

    static String contentType(String fileName) {
        String ext = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        return CONTENT_TYPES.getOrDefault(ext, "application/octet-stream");
    }

    /** Sends a finished result. File results are buffered: the workspace is deleted right after. */
    static void send(Context ctx, Job.Result result) throws IOException {
        if (result.json() != null) {
            ctx.status(HttpStatus.OK).contentType("application/json").header("Cache-Control", "no-store").result(result.json());
            return;
        }
        attachmentHeaders(ctx, result);
        ctx.result(Files.readAllBytes(result.file()));
    }

    static void attachmentHeaders(Context ctx, Job.Result result) {
        String fileName = result.filename();
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        ctx.status(HttpStatus.OK)
                .contentType(result.contentType())
                .header("Content-Disposition", "attachment; filename=\"" + fileName.replaceAll("[^\\x20-\\x7e]|\"", "_")
                        + "\"; filename*=UTF-8''" + encoded)
                .header("Cache-Control", "no-store");
    }

    /** "a.pdf", "a.pdf" → "a.pdf", "a (2).pdf". */
    private static String unique(String name, Set<String> used) {
        String candidate = name;
        int dot = name.lastIndexOf('.');
        for (int n = 2; !used.add(candidate); n++) {
            candidate = name.substring(0, dot) + " (" + n + ")" + name.substring(dot);
        }
        return candidate;
    }

    static String stem(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    static int intParam(Params ctx, String name, int fallback, int min, int max) {
        String raw = ctx.formParam(name);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(raw.strip());
            if (value < min || value > max) {
                throw new BadRequestResponse(name + " must be between " + min + " and " + max);
            }
            return value;
        } catch (NumberFormatException e) {
            throw new BadRequestResponse(name + " must be a number");
        }
    }
}

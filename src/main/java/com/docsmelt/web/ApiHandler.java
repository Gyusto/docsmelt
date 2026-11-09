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
import java.util.concurrent.Semaphore;
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
            "zip", "application/zip");

    private final ServerConfig config;
    private final Semaphore slots;

    ApiHandler(ServerConfig config, Semaphore slots) {
        this.config = config;
        this.slots = slots;
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
    protected abstract Reply process(Context ctx, List<Path> inputs, Workspace workspace);

    protected int minFiles() {
        return 1;
    }

    protected int maxFiles() {
        return config.maxFiles();
    }

    @Override
    public final void handle(Context ctx) throws Exception {
        NaiveRateLimit.requestPerTimeUnit(ctx, config.requestsPerMinute(), TimeUnit.MINUTES);
        List<UploadedFile> uploads = ctx.uploadedFiles("files");
        validate(uploads);

        if (!slots.tryAcquire(30, TimeUnit.SECONDS)) {
            throw new ServiceUnavailableResponse("Server is busy, please try again in a moment");
        }
        long started = System.nanoTime();
        try (Workspace workspace = new Workspace()) {
            List<Path> inputs = new ArrayList<>();
            for (UploadedFile upload : uploads) {
                inputs.add(workspace.save(upload));
            }
            Reply reply = process(ctx, inputs, workspace);
            switch (reply) {
                case Reply.Files f -> respond(ctx, f.files(), f.zipName());
                case Reply.Json j -> ctx.status(HttpStatus.OK).contentType("application/json")
                        .header("Cache-Control", "no-store").result(j.body());
            }
            LOG.info("{} {} file(s) in {} ms", ctx.path(), inputs.size(),
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        } finally {
            slots.release();
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

    /**
     * The workspace is deleted when the handler returns, so the response is
     * buffered in memory first (bounded by the upload limits).
     */
    private static void respond(Context ctx, List<Path> outputs, String zipName) throws IOException {
        if (outputs.size() == 1) {
            Path file = outputs.getFirst();
            send(ctx, file.getFileName().toString(), Files.readAllBytes(file));
            return;
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            Set<String> used = new HashSet<>();
            for (Path file : outputs) {
                zip.putNextEntry(new ZipEntry(unique(file.getFileName().toString(), used)));
                Files.copy(file, zip);
                zip.closeEntry();
            }
        }
        send(ctx, zipName, buffer.toByteArray());
    }

    private static void send(Context ctx, String fileName, byte[] bytes) {
        String ext = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        ctx.status(HttpStatus.OK)
                .contentType(CONTENT_TYPES.getOrDefault(ext, "application/octet-stream"))
                .header("Content-Disposition", "attachment; filename=\"" + fileName.replaceAll("[^\\x20-\\x7e]|\"", "_")
                        + "\"; filename*=UTF-8''" + encoded)
                .header("Cache-Control", "no-store")
                .result(bytes);
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

    static int intParam(Context ctx, String name, int fallback, int min, int max) {
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

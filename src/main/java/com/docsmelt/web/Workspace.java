package com.docsmelt.web;

import io.javalin.http.BadRequestResponse;
import io.javalin.http.UploadedFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * A private temporary directory for one request. Uploads are stored under
 * {@code in/}, results under {@code out/}; everything is deleted on close.
 */
final class Workspace implements AutoCloseable {

    private final Path root;
    private final Path in;
    private final Path out;
    private int uploads;

    Workspace() throws IOException {
        this.root = Files.createTempDirectory("docsmelt-web-");
        this.in = Files.createDirectory(root.resolve("in"));
        this.out = Files.createDirectory(root.resolve("out"));
    }

    /**
     * Saves an upload under a sanitized version of its name. Each upload gets
     * its own sub-folder, so two files called "a.pdf" don't clash and a
     * malicious name like "../../etc/passwd" can't escape the workspace.
     */
    Path save(UploadedFile upload) throws IOException {
        Path dir = Files.createDirectory(in.resolve(String.valueOf(uploads++)));
        Path target = dir.resolve(safeFileName(upload.filename()));
        try (InputStream content = upload.content()) {
            Files.copy(content, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    Path out() {
        return out;
    }

    static String safeFileName(String original) {
        String name = original == null ? "" : original;
        name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1); // drop any path
        name = name.replaceAll("[\\p{Cntrl}<>:\"|?*]", "_").strip();
        while (name.startsWith(".")) {
            name = name.substring(1); // no hidden files, no "..", no empty stem
        }
        if (name.isBlank() || !name.contains(".")) {
            throw new BadRequestResponse("Uploaded file needs a name with an extension, got: " + original);
        }
        if (name.length() > 150) {
            int dot = name.lastIndexOf('.');
            name = name.substring(0, Math.min(dot, 140)) + name.substring(dot);
        }
        return name;
    }

    @Override
    public void close() {
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
            // best effort; the OS cleans temp eventually
        }
    }
}

package com.filekit.core.batch;

import com.filekit.core.FormatRegistry;
import com.filekit.format.Format;
import com.filekit.format.UnsupportedFormatException;
import com.filekit.io.ConversionException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Expands files and directories into {@link ConversionJob}s.
 *
 * <ul>
 *   <li>A file is always converted (it must be a readable format).</li>
 *   <li>A directory contributes every readable file inside it (recursively if
 *       asked); files of other types are silently skipped.</li>
 *   <li>Outputs go next to each input, or under {@code outputDir} keeping the
 *       sub-folder structure of directory inputs.</li>
 * </ul>
 */
public final class JobPlanner {

    private final FormatRegistry registry;

    public JobPlanner(FormatRegistry registry) {
        this.registry = registry;
    }

    /**
     * @param outputDir where to write results, or {@code null} for "next to each input"
     */
    public List<ConversionJob> plan(List<Path> inputs, Format target, Path outputDir, boolean recursive) {
        registry.writerFor(target); // fail fast if the target format can't be written
        List<ConversionJob> jobs = new ArrayList<>();
        for (Path input : inputs) {
            if (Files.isDirectory(input)) {
                for (Path file : readableFilesIn(input, recursive)) {
                    Path relativeDir = Optional.ofNullable(input.relativize(file).getParent()).orElse(Path.of(""));
                    Path dir = outputDir == null ? file.getParent() : outputDir.resolve(relativeDir);
                    Path out = dir.resolve(renamed(file, target));
                    if (!sameFile(file, out)) { // e.g. a.pdf when converting a folder to PDF
                        jobs.add(new ConversionJob(file, out));
                    }
                }
            } else if (Files.isRegularFile(input)) {
                registry.readerFor(Format.fromPath(input)); // fail fast on unsupported inputs
                Path dir = outputDir == null ? input.toAbsolutePath().getParent() : outputDir;
                jobs.add(new ConversionJob(input, dir.resolve(renamed(input, target))));
            } else {
                throw new ConversionException("No such file or directory: " + input);
            }
        }
        rejectDuplicateTargets(jobs);
        return jobs;
    }

    private List<Path> readableFilesIn(Path dir, boolean recursive) {
        try (Stream<Path> files = recursive ? Files.walk(dir) : Files.list(dir)) {
            return files.filter(Files::isRegularFile)
                    .filter(this::isReadable)
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new ConversionException("Cannot list directory " + dir + ": " + e.getMessage(), e);
        }
    }

    private boolean isReadable(Path file) {
        try {
            return registry.readableFormats().contains(Format.fromPath(file));
        } catch (UnsupportedFormatException e) {
            return false;
        }
    }

    private static boolean sameFile(Path a, Path b) {
        return a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
    }

    private static String renamed(Path file, Format target) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        return stem + "." + target.primaryExtension();
    }

    /** e.g. report.docx and report.txt would both become report.pdf. */
    private static void rejectDuplicateTargets(List<ConversionJob> jobs) {
        Map<Path, Path> seen = new HashMap<>();
        for (ConversionJob job : jobs) {
            Path key = job.target().toAbsolutePath().normalize();
            Path previous = seen.putIfAbsent(key, job.source());
            if (previous != null) {
                throw new ConversionException("Both " + previous + " and " + job.source()
                        + " would be written to " + job.target() + "; use --output-dir or rename one");
            }
        }
    }
}

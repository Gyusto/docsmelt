package com.filekit.cli;

import com.filekit.core.ConversionOptions;
import com.filekit.core.Converter;
import com.filekit.core.batch.BatchConverter;
import com.filekit.core.batch.BatchResult;
import com.filekit.core.batch.ConversionJob;
import com.filekit.core.batch.ConversionListener;
import com.filekit.core.batch.JobPlanner;
import com.filekit.format.Format;
import com.filekit.io.ConversionException;
import com.filekit.io.PageRange;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Command(
        name = "filekit",
        mixinStandardHelpOptions = true,
        version = "filekit 0.2.0",
        sortOptions = false,
        subcommands = {MergeCommand.class, SplitCommand.class, ServeCommand.class},
        description = "Convert between PDF, Word, text and image files.",
        footer = {
                "",
                "Examples:",
                "  filekit merge a.pdf photo.jpg b.docx -o all.pdf   (see: filekit merge --help)",
                "  filekit split book.pdf --every 10                 (see: filekit split --help)",
                "  filekit serve                                     web UI at http://127.0.0.1:8080",
                "  filekit report.docx report.pdf               single file",
                "  filekit slides.pdf page.png --dpi 300        -> page-1.png, page-2.png, ...",
                "  filekit scan.pdf cover.jpg --pages 1 -q 80",
                "  filekit --to pdf docs/ notes.txt -o out/ -r  batch (files and folders)",
                "",
                "Exit codes: 0 ok, 1 conversion failed, 2 bad usage"
        })
public final class Main implements Callable<Integer> {

    @Parameters(paramLabel = "FILE", arity = "0..*",
            description = "INPUT OUTPUT, or (with --to) any number of input files/folders.")
    List<Path> files = new ArrayList<>();

    @Option(names = {"-t", "--to"}, paramLabel = "FORMAT",
            description = "Batch mode: convert every input to this format (pdf, docx, png, ...).")
    String to;

    @Option(names = {"-o", "--output-dir"}, paramLabel = "DIR",
            description = "Batch mode: write results here instead of next to each input.")
    Path outputDir;

    @Option(names = {"-r", "--recursive"}, description = "Batch mode: include sub-folders.")
    boolean recursive;

    @Option(names = {"-j", "--jobs"}, paramLabel = "N", defaultValue = "1",
            description = "Batch mode: files to convert in parallel (default: ${DEFAULT-VALUE}).")
    int jobs;

    @Option(names = "--pages", paramLabel = "RANGE", description = "Pages to convert, e.g. 1,3,5-8 or 4- .")
    String pages;

    @Option(names = "--dpi", defaultValue = "150",
            description = "Resolution for rendering pages to images (default: ${DEFAULT-VALUE}).")
    int dpi;

    @Option(names = {"-q", "--quality"}, paramLabel = "1-100", defaultValue = "90",
            description = "JPEG quality (default: ${DEFAULT-VALUE}).")
    int quality;

    @Option(names = "--font", paramLabel = "TTF",
            description = "Fallback .ttf font for characters missing from the built-in font (repeatable). "
                    + "Also read from $FILEKIT_FONTS.")
    List<Path> fonts = new ArrayList<>();

    @Option(names = "--formats", description = "List supported formats and exit.")
    boolean listFormats;

    @Spec
    CommandLine.Model.CommandSpec spec;

    public static void main(String[] args) {
        System.setProperty("java.awt.headless", "true");
        System.exit(run(args));
    }

    public static int run(String... args) {
        return new CommandLine(new Main()).execute(args);
    }

    @Override
    public Integer call() {
        PrintWriter out = spec.commandLine().getOut();
        PrintWriter err = spec.commandLine().getErr();
        Converter converter = new Converter();

        if (listFormats) {
            out.println("Read:  " + names(converter.registry().readableFormats()));
            out.println("Write: " + names(converter.registry().writableFormats()));
            return 0;
        }

        ConversionOptions options = buildOptions();
        try {
            return to == null ? single(converter, options, out) : batch(converter, options, out, err);
        } catch (ConversionException e) {
            err.println("error: " + e.getMessage());
            return 1;
        }
    }

    private ConversionOptions buildOptions() {
        if (quality < 1 || quality > 100) {
            throw usage("--quality must be between 1 and 100");
        }
        try {
            return new ConversionOptions(dpi, PageRange.parse(pages), quality / 100f, fonts);
        } catch (IllegalArgumentException e) {
            throw usage(e.getMessage());
        }
    }

    private int single(Converter converter, ConversionOptions options, PrintWriter out) {
        if (files.size() != 2) {
            throw usage("expected INPUT and OUTPUT (or use --to FORMAT for batch mode)");
        }
        if (outputDir != null || recursive) {
            throw usage("--output-dir and --recursive need --to FORMAT");
        }
        converter.convert(files.get(0), files.get(1), options).forEach(p -> out.println("Wrote " + p));
        return 0;
    }

    private int batch(Converter converter, ConversionOptions options, PrintWriter out, PrintWriter err) {
        if (files.isEmpty()) {
            throw usage("no input files given");
        }
        if (jobs < 1) {
            throw usage("--jobs must be at least 1");
        }
        Format target = Format.fromPath(Path.of("x." + to.toLowerCase(Locale.ROOT).replaceFirst("^\\.", "")));
        List<ConversionJob> planned = new JobPlanner(converter.registry()).plan(files, target, outputDir, recursive);
        if (planned.isEmpty()) {
            err.println("Nothing to convert.");
            return 0;
        }

        AtomicInteger done = new AtomicInteger();
        int total = planned.size();
        Object printLock = new Object();
        ConversionListener progress = new ConversionListener() {
            @Override
            public void succeeded(ConversionJob job, List<Path> outputs) {
                synchronized (printLock) {
                    out.printf("[%d/%d] %s -> %s%n", done.incrementAndGet(), total, job.source(),
                            outputs.size() == 1 ? outputs.getFirst() : outputs.size() + " files");
                    out.flush();
                }
            }

            @Override
            public void failed(ConversionJob job, Exception error) {
                synchronized (printLock) {
                    out.flush();
                    err.printf("[%d/%d] FAILED %s: %s%n", done.incrementAndGet(), total, job.source(),
                            error.getMessage());
                    err.flush();
                }
            }
        };

        BatchResult result = new BatchConverter(converter, jobs).run(planned, options, progress);
        out.printf("Done: %d converted, %d failed.%n", result.successes().size(), result.failures().size());
        return result.allSucceeded() ? 0 : 1;
    }

    private CommandLine.ParameterException usage(String message) {
        return new CommandLine.ParameterException(spec.commandLine(), message);
    }

    private static String names(Set<Format> formats) {
        return formats.stream()
                .flatMap(f -> f.extensions().stream())
                .collect(Collectors.joining(", "));
    }
}

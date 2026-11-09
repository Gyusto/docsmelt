package com.filekit.cli;

import com.filekit.io.ConversionException;
import com.filekit.pdf.PdfSplitter;
import com.filekit.pdf.SplitStrategy;
import picocli.CommandLine;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "split", mixinStandardHelpOptions = true, sortOptions = false,
        description = "Split a PDF into several PDFs (<name>-1.pdf, <name>-2.pdf, ...).",
        footer = {"", "Examples:",
                "  filekit split book.pdf                       one file per page",
                "  filekit split book.pdf --every 10 -o parts/  10 pages per file",
                "  filekit split book.pdf --ranges 1-3,4-9,10-  one file per range",
                "  filekit split book.pdf --ranges 5-7          extract pages 5-7"})
final class SplitCommand implements Callable<Integer> {

    @Parameters(paramLabel = "PDF", description = "PDF to split.")
    Path input;

    @ArgGroup(exclusive = true)
    Mode mode;

    static final class Mode {
        @Option(names = "--every", paramLabel = "N", description = "Pages per output file (default: 1).")
        Integer every;

        @Option(names = "--ranges", paramLabel = "RANGES",
                description = "Comma-separated ranges, one output file each, e.g. 1-3,4,5-.")
        String ranges;
    }

    @Option(names = {"-o", "--output-dir"}, paramLabel = "DIR", description = "Where to write the parts.")
    Path outputDir;

    @Spec
    CommandSpec spec;

    @Override
    public Integer call() {
        SplitStrategy strategy;
        try {
            strategy = mode != null && mode.ranges != null
                    ? SplitStrategy.ByRanges.parse(mode.ranges)
                    : new SplitStrategy.EveryNPages(mode != null && mode.every != null ? mode.every : 1);
        } catch (IllegalArgumentException e) {
            throw new CommandLine.ParameterException(spec.commandLine(), e.getMessage());
        }
        try {
            List<Path> parts = new PdfSplitter().split(input, strategy, outputDir);
            parts.forEach(p -> spec.commandLine().getOut().println("Wrote " + p));
            return 0;
        } catch (ConversionException e) {
            spec.commandLine().getErr().println("error: " + e.getMessage());
            return 1;
        }
    }
}

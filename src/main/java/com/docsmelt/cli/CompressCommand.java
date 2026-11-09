package com.docsmelt.cli;

import com.docsmelt.io.ConversionException;
import com.docsmelt.pdf.PdfCompressor;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.Callable;

@Command(name = "compress", mixinStandardHelpOptions = true, sortOptions = false,
        description = "Make a PDF smaller by shrinking its images. Text stays sharp.",
        footer = {"", "Examples:",
                "  docsmelt compress big.pdf -o small.pdf",
                "  docsmelt compress big.pdf -o tiny.pdf --level high"})
final class CompressCommand implements Callable<Integer> {

    @Parameters(paramLabel = "PDF", description = "PDF to compress.")
    Path input;

    @Option(names = {"-o", "--output"}, paramLabel = "PDF", required = true, description = "Where to save the result.")
    Path output;

    @Option(names = {"-l", "--level"}, defaultValue = "medium",
            description = "low (best quality), medium, or high (smallest). Default: ${DEFAULT-VALUE}.")
    String level;

    @Spec
    CommandSpec spec;

    @Override
    public Integer call() {
        PdfCompressor.Level lvl;
        try {
            lvl = PdfCompressor.Level.parse(level);
        } catch (IllegalArgumentException e) {
            throw new CommandLine.ParameterException(spec.commandLine(), e.getMessage());
        }
        try {
            PdfCompressor.Result r = new PdfCompressor().compress(input, output, lvl);
            String saving = r.compressedBytes() < r.originalBytes()
                    ? String.format(Locale.ROOT, "%s -> %s (-%d%%, %d images)", size(r.originalBytes()),
                    size(r.compressedBytes()), r.savedPercent(), r.imagesRecompressed())
                    : "already compact, kept as is (" + size(r.originalBytes()) + ")";
            spec.commandLine().getOut().printf("Wrote %s: %s%n", output, saving);
            return 0;
        } catch (ConversionException e) {
            spec.commandLine().getErr().println("error: " + e.getMessage());
            return 1;
        }
    }

    static String size(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0);
        }
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024));
    }
}

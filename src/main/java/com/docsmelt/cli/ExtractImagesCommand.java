package com.docsmelt.cli;

import com.docsmelt.io.ConversionException;
import com.docsmelt.io.PageRange;
import com.docsmelt.pdf.PdfImageExtractor;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "extract-images", mixinStandardHelpOptions = true, sortOptions = false,
        description = "Save the pictures inside a PDF. Photos keep their original quality.",
        footer = {"", "Example:", "  docsmelt extract-images brochure.pdf -o images/"})
final class ExtractImagesCommand implements Callable<Integer> {

    @Parameters(paramLabel = "PDF", description = "PDF to take the images from.")
    Path input;

    @Option(names = {"-o", "--output-dir"}, paramLabel = "DIR", description = "Folder for the images (default: next to the PDF).")
    Path outputDir;

    @Option(names = "--pages", paramLabel = "RANGE", description = "Only these pages, e.g. 1-3.")
    String pages;

    @Spec
    CommandSpec spec;

    @Override
    public Integer call() {
        PageRange range;
        try {
            range = PageRange.parse(pages);
        } catch (IllegalArgumentException e) {
            throw new CommandLine.ParameterException(spec.commandLine(), e.getMessage());
        }
        Path dir = outputDir != null ? outputDir : input.toAbsolutePath().getParent();
        try {
            List<Path> images = new PdfImageExtractor().extract(input, dir, range);
            images.forEach(p -> spec.commandLine().getOut().println("Wrote " + p));
            return 0;
        } catch (ConversionException e) {
            spec.commandLine().getErr().println("error: " + e.getMessage());
            return 1;
        }
    }
}

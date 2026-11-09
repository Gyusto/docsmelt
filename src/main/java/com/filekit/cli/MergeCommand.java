package com.filekit.cli;

import com.filekit.core.ConversionOptions;
import com.filekit.core.Converter;
import com.filekit.io.ConversionException;
import com.filekit.io.PageRange;
import com.filekit.pdf.PdfMerger;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "merge", mixinStandardHelpOptions = true, sortOptions = false,
        description = "Merge files into one PDF, in the order given. Word, text and image files "
                + "are converted to PDF automatically.",
        footer = {"", "Example:", "  filekit merge cover.jpg report.docx appendix.pdf -o book.pdf"})
final class MergeCommand implements Callable<Integer> {

    @Parameters(paramLabel = "FILE", arity = "2..*", description = "Files to merge (2 or more).")
    List<Path> inputs = new ArrayList<>();

    @Option(names = {"-o", "--output"}, paramLabel = "PDF", required = true, description = "Merged PDF to write.")
    Path output;

    @Option(names = "--font", paramLabel = "TTF", description = "Fallback .ttf font for converted text (repeatable).")
    List<Path> fonts = new ArrayList<>();

    @Spec
    CommandSpec spec;

    @Override
    public Integer call() {
        try {
            ConversionOptions options = new ConversionOptions(150, PageRange.ALL, 0.9f, fonts);
            int pages = new PdfMerger(new Converter()).merge(inputs, output, options);
            spec.commandLine().getOut().printf("Wrote %s (%d files, %d pages)%n", output, inputs.size(), pages);
            return 0;
        } catch (ConversionException e) {
            spec.commandLine().getErr().println("error: " + e.getMessage());
            return 1;
        }
    }
}

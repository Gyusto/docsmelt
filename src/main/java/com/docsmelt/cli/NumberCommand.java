package com.docsmelt.cli;

import com.docsmelt.io.ConversionException;
import com.docsmelt.io.PageRange;
import com.docsmelt.pdf.PageNumberer;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "number", mixinStandardHelpOptions = true, sortOptions = false,
        description = "Add page numbers to a PDF.",
        footer = {"", "Examples:",
                "  docsmelt number report.pdf -o numbered.pdf",
                "  docsmelt number report.pdf -o numbered.pdf --format \"Page {n} of {total}\" --position bottom-right",
                "  docsmelt number thesis.pdf -o out.pdf --pages 2- --start 0      skip the cover page"})
final class NumberCommand implements Callable<Integer> {

    @Parameters(paramLabel = "PDF", description = "PDF to number.")
    Path input;

    @Option(names = {"-o", "--output"}, paramLabel = "PDF", required = true, description = "Where to save the result.")
    Path output;

    @Option(names = "--position", defaultValue = "bottom-center",
            description = "bottom-left|bottom-center|bottom-right|top-left|top-center|top-right. Default: ${DEFAULT-VALUE}.")
    String position;

    @Option(names = "--format", defaultValue = "{n}",
            description = "Label with {n} and optionally {total}. Default: ${DEFAULT-VALUE}.")
    String format;

    @Option(names = "--start", defaultValue = "1", description = "Number of the first page. Default: ${DEFAULT-VALUE}.")
    int start;

    @Option(names = "--pages", paramLabel = "RANGE", description = "Pages that get a number, e.g. 2- (others are still counted).")
    String pages;

    @Option(names = "--size", defaultValue = "10", description = "Font size in points. Default: ${DEFAULT-VALUE}.")
    float size;

    @Spec
    CommandSpec spec;

    @Override
    public Integer call() {
        PageNumberer.Options options;
        try {
            options = new PageNumberer.Options(PageNumberer.Position.parse(position), format, start,
                    PageRange.parse(pages), size);
        } catch (IllegalArgumentException e) {
            throw new CommandLine.ParameterException(spec.commandLine(), e.getMessage());
        }
        try {
            int n = new PageNumberer().apply(input, output, options);
            spec.commandLine().getOut().printf("Wrote %s (%d pages numbered)%n", output, n);
            return 0;
        } catch (ConversionException e) {
            spec.commandLine().getErr().println("error: " + e.getMessage());
            return 1;
        }
    }
}

package com.docsmelt.cli;

import com.docsmelt.io.ConversionException;
import com.docsmelt.pdf.PdfAConverter;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "pdfa", mixinStandardHelpOptions = true, sortOptions = false,
        description = "Convert to PDF/A-2b for long-term archiving (needs Ghostscript).",
        footer = {"", "Example:", "  docsmelt pdfa thesis.pdf -o thesis-archive.pdf"})
final class PdfACommand implements Callable<Integer> {

    @Parameters(paramLabel = "PDF", description = "PDF to convert.")
    Path input;

    @Option(names = {"-o", "--output"}, paramLabel = "PDF", required = true, description = "Where to save the PDF/A file.")
    Path output;

    @Spec
    CommandSpec spec;

    @Override
    public Integer call() {
        try {
            new PdfAConverter().convert(input, output);
            spec.commandLine().getOut().println("Wrote " + output + " (PDF/A-2b)");
            return 0;
        } catch (ConversionException e) {
            spec.commandLine().getErr().println("error: " + e.getMessage());
            return 1;
        }
    }
}

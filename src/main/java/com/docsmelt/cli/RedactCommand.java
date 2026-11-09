package com.docsmelt.cli;

import com.docsmelt.io.ConversionException;
import com.docsmelt.pdf.PdfRedactor;
import com.docsmelt.pdf.PdfTextFinder;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "redact", mixinStandardHelpOptions = true, sortOptions = false,
        description = {"Permanently black out text or areas. Redacted pages are turned into images,",
                "so the hidden content is really gone (not just covered)."},
        footer = {"", "Examples:",
                "  docsmelt redact contract.pdf -o public.pdf --find \"John Doe\" --find 0712345678",
                "  docsmelt redact scan.pdf -o public.pdf --box 1:0.1,0.2,0.5,0.05"})
final class RedactCommand implements Callable<Integer> {

    @Parameters(paramLabel = "PDF", description = "PDF to redact.")
    Path input;

    @Option(names = {"-o", "--output"}, paramLabel = "PDF", required = true, description = "Where to save the result.")
    Path output;

    @Option(names = "--find", paramLabel = "TEXT", description = "Redact every occurrence of this text (repeatable, case-insensitive).")
    List<String> find = new ArrayList<>();

    @Option(names = "--box", paramLabel = "PAGE:X,Y,W,H", description = "Redact an area, as fractions of the page (repeatable).")
    List<String> boxes = new ArrayList<>();

    @Option(names = "--keep-metadata", description = "Keep author/title metadata (removed by default).")
    boolean keepMetadata;

    @Spec
    CommandSpec spec;

    @Override
    public Integer call() {
        List<PdfRedactor.Box> all = new ArrayList<>();
        try {
            boxes.forEach(b -> all.add(PdfRedactor.Box.parse(b)));
            for (String text : find) {
                List<PdfRedactor.Box> found = new PdfTextFinder().find(input, text);
                spec.commandLine().getOut().printf("\"%s\": %d match(es)%n", text, found.size());
                all.addAll(found);
            }
            if (all.isEmpty()) {
                spec.commandLine().getErr().println("error: nothing to redact");
                return 1;
            }
            int pages = new PdfRedactor().redact(input, output, all, !keepMetadata);
            spec.commandLine().getOut().printf("Wrote %s (%d areas on %d pages redacted)%n", output, all.size(), pages);
            return 0;
        } catch (IllegalArgumentException e) {
            throw new CommandLine.ParameterException(spec.commandLine(), e.getMessage());
        } catch (ConversionException e) {
            spec.commandLine().getErr().println("error: " + e.getMessage());
            return 1;
        }
    }
}

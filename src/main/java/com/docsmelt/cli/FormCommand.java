package com.docsmelt.cli;

import com.docsmelt.io.ConversionException;
import com.docsmelt.pdf.PdfFormFiller;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(name = "form", mixinStandardHelpOptions = true, sortOptions = false,
        description = "List or fill the fields of a fillable PDF form.",
        footer = {"", "Examples:",
                "  docsmelt form application.pdf                                   list the fields",
                "  docsmelt form application.pdf -o filled.pdf --set \"Name=Asha\" --set agree=true --flatten"})
final class FormCommand implements Callable<Integer> {

    @Parameters(paramLabel = "PDF", description = "The form.")
    Path input;

    @Option(names = {"-o", "--output"}, paramLabel = "PDF", description = "Where to save the filled form.")
    Path output;

    @Option(names = "--set", paramLabel = "NAME=VALUE", description = "Field value (repeatable). Checkboxes take true/false.")
    Map<String, String> values = new LinkedHashMap<>();

    @Option(names = "--flatten", description = "Make the answers part of the page so they can't be changed.")
    boolean flatten;

    @Spec
    CommandSpec spec;

    @Override
    public Integer call() {
        PdfFormFiller forms = new PdfFormFiller();
        try {
            if (output == null) {
                for (PdfFormFiller.Field f : forms.fields(input)) {
                    spec.commandLine().getOut().printf("%-30s %-8s %s%s%n", f.name(), f.type().name().toLowerCase(),
                            f.value().isEmpty() ? "" : "= " + f.value(),
                            f.options().isEmpty() ? "" : "  options: " + String.join(", ", f.options()));
                }
                return 0;
            }
            if (values.isEmpty() && !flatten) {
                throw new CommandLine.ParameterException(spec.commandLine(), "use --set NAME=VALUE (or --flatten)");
            }
            int n = forms.fill(input, output, values, flatten);
            spec.commandLine().getOut().printf("Wrote %s (%d fields filled%s)%n", output, n, flatten ? ", flattened" : "");
            return 0;
        } catch (ConversionException e) {
            spec.commandLine().getErr().println("error: " + e.getMessage());
            return 1;
        }
    }
}

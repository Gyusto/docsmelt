package com.docsmelt.cli;

import com.docsmelt.io.ConversionException;
import com.docsmelt.pdf.PdfSecurity;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "unlock", mixinStandardHelpOptions = true, sortOptions = false,
        description = "Remove the password from a PDF (you need to know it).",
        footer = {"", "Example:", "  docsmelt unlock locked.pdf -o open.pdf --password"})
final class UnlockCommand implements Callable<Integer> {

    @Parameters(paramLabel = "PDF", description = "Password-protected PDF.")
    Path input;

    @Option(names = {"-o", "--output"}, paramLabel = "PDF", required = true, description = "Where to save the result.")
    Path output;

    @Option(names = "--password", required = true, interactive = true, arity = "0..1",
            description = "The PDF's password. Leave the value out to be prompted.")
    String password;

    @Spec
    CommandSpec spec;

    @Override
    public Integer call() {
        try {
            new PdfSecurity().unlock(input, output, password);
            spec.commandLine().getOut().println("Wrote " + output + " (password removed)");
            return 0;
        } catch (ConversionException e) {
            spec.commandLine().getErr().println("error: " + e.getMessage());
            return 1;
        }
    }
}

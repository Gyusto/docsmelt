package com.docsmelt.cli;

import com.docsmelt.io.ConversionException;
import com.docsmelt.pdf.PdfSecurity;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "protect", mixinStandardHelpOptions = true, sortOptions = false,
        description = "Add a password to a PDF (AES-256).",
        footer = {"", "Examples:",
                "  docsmelt protect report.pdf -o locked.pdf --password            asks for it (not saved in history)",
                "  docsmelt protect report.pdf -o locked.pdf --password --no-copy --no-print"})
final class ProtectCommand implements Callable<Integer> {

    @Parameters(paramLabel = "PDF", description = "PDF to protect.")
    Path input;

    @Option(names = {"-o", "--output"}, paramLabel = "PDF", required = true, description = "Where to save the result.")
    Path output;

    @Option(names = "--password", required = true, interactive = true, arity = "0..1",
            description = "Password needed to open the file. Leave the value out to be prompted.")
    String password;

    @Option(names = "--owner-password", interactive = true, arity = "0..1",
            description = "Separate password that lifts the restrictions (default: random, unknown).")
    String ownerPassword;

    @Option(names = "--no-print", description = "Don't allow printing.")
    boolean noPrint;

    @Option(names = "--no-copy", description = "Don't allow copying text and images.")
    boolean noCopy;

    @Option(names = "--no-edit", description = "Don't allow editing.")
    boolean noEdit;

    @Spec
    CommandSpec spec;

    @Override
    public Integer call() {
        try {
            new PdfSecurity().protect(input, output, password, ownerPassword,
                    new PdfSecurity.Permissions(!noPrint, !noCopy, !noEdit));
            spec.commandLine().getOut().println("Wrote " + output + " (password protected)");
            return 0;
        } catch (IllegalArgumentException e) {
            throw new CommandLine.ParameterException(spec.commandLine(), e.getMessage());
        } catch (ConversionException e) {
            spec.commandLine().getErr().println("error: " + e.getMessage());
            return 1;
        }
    }
}

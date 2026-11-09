package com.docsmelt.cli;

import com.docsmelt.io.ConversionException;
import com.docsmelt.pdf.PdfSigner;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "sign", mixinStandardHelpOptions = true, sortOptions = false,
        description = {"Place a signature image on a PDF page.",
                "This is a visual signature (like signing a printout), not a cryptographic one."},
        footer = {"", "Examples:",
                "  docsmelt sign contract.pdf --signature sig.png -o signed.pdf          bottom right, last page",
                "  docsmelt sign contract.pdf --signature sig.png -o signed.pdf --page 2 --x 0.1 --y 0.75 --width 0.25"})
final class SignCommand implements Callable<Integer> {

    private static final PdfSigner.Placement DEFAULTS = PdfSigner.Placement.defaults();

    @Parameters(paramLabel = "PDF", description = "PDF to sign.")
    Path input;

    @Option(names = "--signature", paramLabel = "IMAGE", required = true,
            description = "Signature image; a PNG with a transparent background looks best.")
    Path signature;

    @Option(names = {"-o", "--output"}, paramLabel = "PDF", required = true, description = "Where to save the result.")
    Path output;

    @Option(names = "--page", defaultValue = "0", description = "Page to sign (0 = last page). Default: last.")
    int page;

    @Option(names = "--x", description = "Left edge, fraction of page width from the left (0-1).")
    Float x;

    @Option(names = "--y", description = "Top edge, fraction of page height from the top (0-1).")
    Float y;

    @Option(names = "--width", description = "Signature width as a fraction of the page width.")
    Float width;

    @Spec
    CommandSpec spec;

    @Override
    public Integer call() {
        PdfSigner.Placement placement;
        try {
            placement = new PdfSigner.Placement(page,
                    x != null ? x : DEFAULTS.x(), y != null ? y : DEFAULTS.y(), width != null ? width : DEFAULTS.width());
        } catch (IllegalArgumentException e) {
            throw new CommandLine.ParameterException(spec.commandLine(), e.getMessage());
        }
        try {
            int signed = new PdfSigner().sign(input, signature, output, placement);
            spec.commandLine().getOut().printf("Wrote %s (signed page %d)%n", output, signed);
            return 0;
        } catch (ConversionException e) {
            spec.commandLine().getErr().println("error: " + e.getMessage());
            return 1;
        }
    }
}

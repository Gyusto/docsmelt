package com.docsmelt.cli;

import com.docsmelt.io.ConversionException;
import com.docsmelt.io.PageRange;
import com.docsmelt.pdf.PdfWatermarker;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.awt.Color;
import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "watermark", mixinStandardHelpOptions = true, sortOptions = false,
        description = "Stamp text or an image (e.g. a logo) on PDF pages.",
        footer = {"", "Examples:",
                "  docsmelt watermark report.pdf -o draft.pdf --text CONFIDENTIAL",
                "  docsmelt watermark report.pdf -o draft.pdf --text \"DRAFT\" --layout tile --opacity 0.15",
                "  docsmelt watermark report.pdf -o branded.pdf --image logo.png --angle 0 --size 0.3"})
final class WatermarkCommand implements Callable<Integer> {

    @Parameters(paramLabel = "PDF", description = "PDF to watermark.")
    Path input;

    @Option(names = {"-o", "--output"}, paramLabel = "PDF", required = true, description = "Where to save the result.")
    Path output;

    @Option(names = "--text", description = "Watermark text.")
    String text;

    @Option(names = "--image", paramLabel = "IMAGE", description = "Watermark image instead of text (PNG with transparency works best).")
    Path image;

    @Option(names = "--color", defaultValue = "#c73a2a", description = "Text colour as #rrggbb. Default: ${DEFAULT-VALUE}.")
    String color;

    @Option(names = "--opacity", defaultValue = "0.25", description = "0.05 (faint) to 1 (solid). Default: ${DEFAULT-VALUE}.")
    float opacity;

    @Option(names = "--angle", defaultValue = "45", description = "Degrees, -90 to 90. Default: ${DEFAULT-VALUE}.")
    float angle;

    @Option(names = "--size", defaultValue = "0", description = "Width as a fraction of the page (0 = automatic).")
    float size;

    @Option(names = "--layout", defaultValue = "center", description = "center or tile. Default: ${DEFAULT-VALUE}.")
    String layout;

    @Option(names = "--pages", paramLabel = "RANGE", description = "Pages to watermark, e.g. 2-.")
    String pages;

    @Spec
    CommandSpec spec;

    @Override
    public Integer call() {
        PdfWatermarker.Options options;
        try {
            if ((text == null) == (image == null)) {
                throw new IllegalArgumentException("use either --text or --image");
            }
            options = new PdfWatermarker.Options(text, image, parseColor(color), opacity, angle, size,
                    PdfWatermarker.Layout.parse(layout), PageRange.parse(pages));
        } catch (IllegalArgumentException e) {
            throw new CommandLine.ParameterException(spec.commandLine(), e.getMessage());
        }
        try {
            int n = new PdfWatermarker().apply(input, output, options);
            spec.commandLine().getOut().printf("Wrote %s (%d pages watermarked)%n", output, n);
            return 0;
        } catch (ConversionException e) {
            spec.commandLine().getErr().println("error: " + e.getMessage());
            return 1;
        }
    }

    static Color parseColor(String hex) {
        String h = hex.strip().replaceFirst("^#", "");
        if (!h.matches("[0-9a-fA-F]{6}")) {
            throw new IllegalArgumentException("Colour must look like #c73a2a");
        }
        return new Color(Integer.parseInt(h, 16));
    }
}

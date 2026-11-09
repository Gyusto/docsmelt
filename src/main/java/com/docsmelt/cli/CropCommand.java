package com.docsmelt.cli;

import com.docsmelt.io.ConversionException;
import com.docsmelt.pdf.PdfPageSizer;
import picocli.CommandLine;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "crop", mixinStandardHelpOptions = true, sortOptions = false,
        description = "Trim page margins (automatically, or by a fixed amount), or resize pages to a paper size.",
        footer = {"", "Examples:",
                "  docsmelt crop scan.pdf -o trimmed.pdf                 trim white margins automatically",
                "  docsmelt crop scan.pdf -o trimmed.pdf --margin 10     cut 10 mm from every side",
                "  docsmelt crop letter.pdf -o a4.pdf --resize a4        fit every page onto A4"})
final class CropCommand implements Callable<Integer> {

    @Parameters(paramLabel = "PDF", description = "PDF to crop or resize.")
    Path input;

    @Option(names = {"-o", "--output"}, paramLabel = "PDF", required = true, description = "Where to save the result.")
    Path output;

    @ArgGroup(exclusive = true)
    Mode mode;

    static final class Mode {
        @Option(names = "--margin", paramLabel = "MM", description = "Cut this many millimetres from every side.")
        Float margin;

        @Option(names = "--resize", paramLabel = "PAPER", description = "Resize pages to a4, a3, a5, letter or legal.")
        String resize;
    }

    @Spec
    CommandSpec spec;

    @Override
    public Integer call() {
        try {
            PdfPageSizer sizer = new PdfPageSizer();
            if (mode != null && mode.resize != null) {
                int n = sizer.resize(input, output, PdfPageSizer.Paper.parse(mode.resize));
                spec.commandLine().getOut().printf("Wrote %s (%d pages resized)%n", output, n);
            } else {
                float margin = mode != null && mode.margin != null ? mode.margin : -1;
                if (mode != null && mode.margin != null && margin < 0) {
                    throw new IllegalArgumentException("--margin can't be negative");
                }
                int n = sizer.crop(input, output, margin);
                spec.commandLine().getOut().printf("Wrote %s (%d pages cropped)%n", output, n);
            }
            return 0;
        } catch (IllegalArgumentException e) {
            throw new CommandLine.ParameterException(spec.commandLine(), e.getMessage());
        } catch (ConversionException e) {
            spec.commandLine().getErr().println("error: " + e.getMessage());
            return 1;
        }
    }
}

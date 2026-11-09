package com.docsmelt.cli;

import com.docsmelt.io.ConversionException;
import com.docsmelt.pdf.ImagesToPdf;
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

@Command(name = "images", mixinStandardHelpOptions = true, sortOptions = false,
        description = "Combine images into one PDF, one image per page. JPEGs keep their original quality.",
        footer = {"", "Examples:",
                "  docsmelt images scan1.jpg scan2.jpg scan3.jpg -o scans.pdf",
                "  docsmelt images photos/*.png -o album.pdf --page-size fit --margin 0"})
final class ImagesCommand implements Callable<Integer> {

    @Parameters(paramLabel = "IMAGE", arity = "1..*", description = "Images, in page order.")
    List<Path> images = new ArrayList<>();

    @Option(names = {"-o", "--output"}, paramLabel = "PDF", required = true, description = "PDF to write.")
    Path output;

    @Option(names = "--page-size", defaultValue = "a4",
            description = "a4, letter, or fit (page = image size). Default: ${DEFAULT-VALUE}.")
    String pageSize;

    @Option(names = "--margin", defaultValue = "20", paramLabel = "PT",
            description = "Margin in points (72 = 1 inch). Default: ${DEFAULT-VALUE}.")
    float margin;

    @Spec
    CommandSpec spec;

    @Override
    public Integer call() {
        ImagesToPdf.PageSize size;
        try {
            size = ImagesToPdf.PageSize.parse(pageSize);
        } catch (IllegalArgumentException e) {
            throw new CommandLine.ParameterException(spec.commandLine(), e.getMessage());
        }
        try {
            int pages = new ImagesToPdf().create(images, output, size, margin);
            spec.commandLine().getOut().printf("Wrote %s (%d pages)%n", output, pages);
            return 0;
        } catch (ConversionException | IllegalArgumentException e) {
            spec.commandLine().getErr().println("error: " + e.getMessage());
            return 1;
        }
    }
}

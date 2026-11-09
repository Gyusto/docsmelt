package com.docsmelt.cli;

import com.docsmelt.io.ConversionException;
import com.docsmelt.io.PageRange;
import com.docsmelt.pdf.PageEditPlan;
import com.docsmelt.pdf.PdfPageEditor;
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

@Command(name = "pages", mixinStandardHelpOptions = true, sortOptions = false,
        description = "Rotate, delete or reorder PDF pages. Page numbers refer to the original file.",
        footer = {"", "Examples:",
                "  docsmelt pages scan.pdf --rotate 90 -o fixed.pdf             rotate every page",
                "  docsmelt pages scan.pdf --rotate 180:2,4 -o fixed.pdf        rotate pages 2 and 4",
                "  docsmelt pages report.pdf --delete 3,7-9 -o short.pdf",
                "  docsmelt pages report.pdf --order 5,1-4 -o moved.pdf         page 5 first"})
final class PagesCommand implements Callable<Integer> {

    @Parameters(paramLabel = "PDF", description = "PDF to edit.")
    Path input;

    @Option(names = {"-o", "--output"}, paramLabel = "PDF", required = true, description = "Where to save the result.")
    Path output;

    @Option(names = "--rotate", paramLabel = "DEG[:PAGES]",
            description = "Rotate clockwise by 90/180/270 (negative = counter-clockwise); repeatable.")
    List<String> rotations = new ArrayList<>();

    @Option(names = "--delete", paramLabel = "PAGES", description = "Pages to remove, e.g. 3,7-9.")
    String delete;

    @Option(names = "--order", paramLabel = "PAGES",
            description = "New page order, e.g. 5,1-4. Pages not listed are removed.")
    String order;

    @Spec
    CommandSpec spec;

    @Override
    public Integer call() {
        PageEditPlan plan = new PageEditPlan();
        try {
            if (order != null) {
                plan.order(expand(order));
            }
            if (delete != null) {
                plan.delete(PageRange.parse(delete));
            }
            rotations.forEach(plan::rotate);
        } catch (IllegalArgumentException e) {
            throw new CommandLine.ParameterException(spec.commandLine(), e.getMessage());
        }
        if (plan.isEmpty()) {
            throw new CommandLine.ParameterException(spec.commandLine(), "nothing to do: use --rotate, --delete or --order");
        }
        try {
            int pages = new PdfPageEditor().apply(input, plan, output);
            spec.commandLine().getOut().printf("Wrote %s (%d pages)%n", output, pages);
            return 0;
        } catch (ConversionException e) {
            spec.commandLine().getErr().println("error: " + e.getMessage());
            return 1;
        }
    }

    /** "5,1-4" → [5, 1, 2, 3, 4]. Open ranges aren't allowed here. */
    private static List<Integer> expand(String spec) {
        List<Integer> pages = new ArrayList<>();
        for (String part : spec.split(",")) {
            String p = part.strip();
            int dash = p.indexOf('-');
            try {
                if (dash > 0 && dash < p.length() - 1) {
                    int from = Integer.parseInt(p.substring(0, dash).strip());
                    int to = Integer.parseInt(p.substring(dash + 1).strip());
                    if (to < from) {
                        throw new NumberFormatException();
                    }
                    for (int i = from; i <= to; i++) {
                        pages.add(i);
                    }
                } else {
                    pages.add(Integer.parseInt(p));
                }
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid page order '" + spec + "' (use e.g. 5,1-4)");
            }
        }
        return pages;
    }
}

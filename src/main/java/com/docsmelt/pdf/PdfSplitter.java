package com.docsmelt.pdf;

import com.docsmelt.format.Format;
import com.docsmelt.io.ConversionException;
import org.apache.pdfbox.pdmodel.PDDocument;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Splits a PDF into several PDFs according to a {@link SplitStrategy}. */
public final class PdfSplitter {

    /**
     * @param outputDir where to write {@code <name>-1.pdf}, {@code <name>-2.pdf}, ...;
     *                  {@code null} means next to the source
     * @return the files written
     */
    public List<Path> split(Path source, SplitStrategy strategy, Path outputDir) {
        if (Format.fromPath(source) != Format.PDF) {
            throw new ConversionException("Only PDF files can be split: " + source);
        }
        Path dir = outputDir != null ? outputDir : source.toAbsolutePath().getParent();
        String stem = source.getFileName().toString().replaceFirst("(?i)\\.pdf$", "");

        try (PDDocument pdf = PdfMerger.load(source, source)) {
            List<List<Integer>> groups = strategy.groups(pdf.getNumberOfPages());
            Files.createDirectories(dir);
            List<Path> written = new ArrayList<>();
            for (int g = 0; g < groups.size(); g++) {
                Path out = dir.resolve(stem + "-" + (g + 1) + ".pdf");
                try (PDDocument part = new PDDocument()) {
                    for (int pageNumber : groups.get(g)) {
                        part.importPage(pdf.getPage(pageNumber - 1));
                    }
                    part.save(out.toFile());
                }
                written.add(out);
            }
            return written;
        } catch (IOException e) {
            throw new ConversionException("Could not split " + source + ": " + e.getMessage(), e);
        }
    }
}

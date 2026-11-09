package com.docsmelt.pdf;

import com.docsmelt.format.Format;
import com.docsmelt.io.ConversionException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageTree;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Rotates, deletes and reorders pages of a PDF according to a {@link PageEditPlan}. */
public final class PdfPageEditor {

    /** @return the number of pages in the result */
    public int apply(Path source, PageEditPlan plan, Path target) {
        if (Format.fromPath(source) != Format.PDF) {
            throw new ConversionException("Only PDF pages can be edited: " + source);
        }
        if (source.toAbsolutePath().normalize().equals(target.toAbsolutePath().normalize())) {
            throw new ConversionException("Output would overwrite the input: " + source);
        }
        try (PDDocument pdf = PdfMerger.load(source, source)) {
            PDPageTree tree = pdf.getPages();
            List<PDPage> original = new ArrayList<>();
            tree.forEach(original::add);
            List<PageEditPlan.PageSpec> specs = plan.resolve(original.size());

            // Rebuild the page tree in the new order. Pages stay the same objects, so
            // their content, annotations and links are kept.
            for (int i = tree.getCount() - 1; i >= 0; i--) {
                tree.remove(i);
            }
            for (PageEditPlan.PageSpec spec : specs) {
                PDPage page = original.get(spec.sourcePage() - 1);
                page.setRotation(Math.floorMod(page.getRotation() + spec.extraRotation(), 360));
                tree.add(page);
            }
            Files.createDirectories(target.toAbsolutePath().getParent());
            pdf.save(target.toFile());
            return specs.size();
        } catch (IOException e) {
            throw new ConversionException("Could not edit " + source + ": " + e.getMessage(), e);
        }
    }
}

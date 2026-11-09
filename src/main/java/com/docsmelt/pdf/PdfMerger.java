package com.docsmelt.pdf;

import com.docsmelt.core.ConversionOptions;
import com.docsmelt.core.Converter;
import com.docsmelt.format.Format;
import com.docsmelt.io.ConversionException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
import org.apache.pdfbox.pdmodel.PDDocument;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Merges several files into one PDF, in the given order. Inputs that are not
 * PDFs (Word, images, text) are converted to PDF first, so
 * {@code merge cover.jpg report.docx appendix.pdf} just works.
 */
public final class PdfMerger {

    private final Converter converter;

    public PdfMerger(Converter converter) {
        this.converter = converter;
    }

    /** @return the number of pages in the merged PDF */
    public int merge(List<Path> inputs, Path target, ConversionOptions options) {
        if (inputs.size() < 2) {
            throw new ConversionException("Merging needs at least two input files");
        }
        for (Path input : inputs) {
            if (input.toAbsolutePath().normalize().equals(target.toAbsolutePath().normalize())) {
                throw new ConversionException("Output would overwrite an input: " + target);
            }
        }

        Path tempDir = null;
        // Sources must stay open until the result is saved: merged pages share their resources.
        List<PDDocument> open = new ArrayList<>();
        try (PDDocument result = new PDDocument()) {
            PDFMergerUtility merger = new PDFMergerUtility();
            for (Path input : inputs) {
                Path pdf = input;
                if (Format.fromPath(input) != Format.PDF) {
                    if (tempDir == null) {
                        tempDir = Files.createTempDirectory("docsmelt-merge");
                    }
                    pdf = tempDir.resolve(open.size() + ".pdf");
                    converter.convert(input, pdf, options);
                }
                PDDocument source = load(pdf, input);
                open.add(source);
                merger.appendDocument(result, source);
            }
            Path parent = target.toAbsolutePath().getParent();
            Files.createDirectories(parent);
            result.save(target.toFile());
            return result.getNumberOfPages();
        } catch (IOException e) {
            throw new ConversionException("Could not merge into " + target + ": " + e.getMessage(), e);
        } finally {
            open.forEach(PdfMerger::closeQuietly);
            deleteQuietly(tempDir);
        }
    }

    static PDDocument load(Path pdf, Path shownAs) {
        if (!Files.isRegularFile(pdf)) {
            throw new ConversionException("Input file not found: " + shownAs);
        }
        try {
            return Loader.loadPDF(pdf.toFile());
        } catch (org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException e) {
            throw new ConversionException(shownAs.getFileName() + " is password-protected; unlock it first", e);
        } catch (IOException e) {
            throw new ConversionException("Could not read PDF " + shownAs + ": " + e.getMessage(), e);
        }
    }

    private static void closeQuietly(PDDocument doc) {
        try {
            doc.close();
        } catch (IOException ignored) {
            // nothing useful to do
        }
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null) {
            return;
        }
        try (var files = Files.list(dir)) {
            for (Path f : files.toList()) {
                Files.deleteIfExists(f);
            }
            Files.deleteIfExists(dir);
        } catch (IOException ignored) {
            // temp files are cleaned by the OS eventually
        }
    }
}

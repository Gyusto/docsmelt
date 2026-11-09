package com.filekit.io.pdf;

import com.filekit.io.ConversionException;
import com.filekit.io.DocumentReader;
import com.filekit.io.ReadOptions;
import com.filekit.model.Document;
import com.filekit.model.ImageBlock;
import com.filekit.model.Page;
import com.filekit.model.Paragraph;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Reads PDFs with Apache PDFBox: extracts text paragraphs and embedded images
 * per page, and optionally renders each page as a snapshot.
 */
public final class PdfDocumentReader implements DocumentReader {

    private static final String PARAGRAPH_BREAK = "\u0000PARA\u0000";

    @Override
    public Document read(Path source, ReadOptions options) {
        try (PDDocument pdf = Loader.loadPDF(source.toFile())) {
            Document document = new Document();
            PDFTextStripper stripper = newStripper();
            PDFRenderer renderer = options.renderSnapshots() ? new PDFRenderer(pdf) : null;

            int total = pdf.getNumberOfPages();
            for (int i = 0; i < total; i++) {
                if (!options.pages().includes(i + 1)) {
                    continue; // skipped pages are never extracted or rendered
                }
                Page page = document.newPage();
                addText(page, stripper, pdf, i + 1);
                addImages(page, pdf.getPage(i));
                if (renderer != null) {
                    page.withSnapshot(renderer.renderImageWithDPI(i, options.dpi(), ImageType.RGB));
                }
            }
            options.pages().requireAny(document.pages().size(), total);
            return document;
        } catch (IOException e) {
            throw new ConversionException("Could not read PDF " + source + ": " + e.getMessage(), e);
        }
    }

    private static PDFTextStripper newStripper() {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setSortByPosition(true);
        stripper.setParagraphEnd(PARAGRAPH_BREAK);
        return stripper;
    }

    private static void addText(Page page, PDFTextStripper stripper, PDDocument pdf, int pageNumber)
            throws IOException {
        stripper.setStartPage(pageNumber);
        stripper.setEndPage(pageNumber);
        String text = stripper.getText(pdf);
        for (String chunk : text.split(PARAGRAPH_BREAK)) {
            String paragraph = chunk.replaceAll("\\s+", " ").strip();
            if (!paragraph.isEmpty()) {
                page.add(Paragraph.body(paragraph));
            }
        }
    }

    /** Top-level image XObjects on the page (images nested in forms are skipped). */
    private static void addImages(Page page, PDPage pdPage) throws IOException {
        PDResources resources = pdPage.getResources();
        if (resources == null) {
            return;
        }
        for (COSName name : resources.getXObjectNames()) {
            if (resources.getXObject(name) instanceof PDImageXObject image) {
                page.add(new ImageBlock(image.getImage()));
            }
        }
    }
}

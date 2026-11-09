package com.docsmelt.io.docx;

import com.docsmelt.io.ConversionException;
import com.docsmelt.io.DocumentReader;
import com.docsmelt.io.ReadOptions;
import com.docsmelt.model.Document;
import com.docsmelt.model.ImageBlock;
import com.docsmelt.model.Page;
import com.docsmelt.model.Paragraph;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFPicture;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Reads .docx files with Apache POI. Word has no fixed pages, so the whole body
 * becomes one {@link Page}; explicit page breaks are not tracked.
 */
public final class DocxDocumentReader implements DocumentReader {

    @Override
    public Document read(Path source, ReadOptions options) {
        try (InputStream in = Files.newInputStream(source);
             XWPFDocument docx = new XWPFDocument(in)) {
            Document document = new Document();
            Page page = document.newPage();
            for (IBodyElement element : docx.getBodyElements()) {
                switch (element) {
                    case XWPFParagraph p -> readParagraph(p, page);
                    case XWPFTable t -> readTable(t, page);
                    default -> { /* content controls etc. are ignored */ }
                }
            }
            return options.pages().select(document);
        } catch (IOException | RuntimeException e) {
            throw new ConversionException("Could not read Word file " + source + ": " + e.getMessage(), e);
        }
    }

    private static void readParagraph(XWPFParagraph p, Page page) throws IOException {
        String text = p.getText().strip();
        if (!text.isEmpty()) {
            page.add(isHeading(p) ? Paragraph.heading(text) : Paragraph.body(text));
        }
        for (XWPFRun run : p.getRuns()) {
            for (XWPFPicture picture : run.getEmbeddedPictures()) {
                BufferedImage image = ImageIO.read(new ByteArrayInputStream(picture.getPictureData().getData()));
                if (image != null) { // null for formats ImageIO can't decode (EMF, WMF, ...)
                    page.add(new ImageBlock(image));
                }
            }
        }
    }

    /** Tables are flattened: one paragraph per row, cells separated by " | ". */
    private static void readTable(XWPFTable table, Page page) {
        for (XWPFTableRow row : table.getRows()) {
            String line = row.getTableCells().stream()
                    .map(XWPFTableCell::getText)
                    .map(String::strip)
                    .collect(Collectors.joining(" | "));
            if (!line.isBlank()) {
                page.add(Paragraph.body(line));
            }
        }
    }

    private static boolean isHeading(XWPFParagraph p) {
        String style = p.getStyle();
        if (style == null) {
            return false;
        }
        String s = style.toLowerCase(Locale.ROOT);
        return s.startsWith("heading") || s.equals("title");
    }
}

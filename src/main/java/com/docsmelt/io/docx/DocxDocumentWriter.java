package com.docsmelt.io.docx;

import com.docsmelt.io.ConversionException;
import com.docsmelt.io.DocumentWriter;
import com.docsmelt.io.WriteOptions;
import com.docsmelt.model.Block;
import com.docsmelt.model.Document;
import com.docsmelt.model.ImageBlock;
import com.docsmelt.model.Page;
import com.docsmelt.model.Paragraph;
import com.docsmelt.model.Table;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.util.Units;
import org.apache.poi.xwpf.usermodel.BreakType;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Writes .docx files with Apache POI; pages are separated by page breaks. */
public final class DocxDocumentWriter implements DocumentWriter {

    /** Printable width of a default Word page (A4/Letter minus margins), in points. */
    private static final double MAX_IMAGE_WIDTH_PT = 450;
    private static final int HEADING_FONT_SIZE = 16;

    @Override
    public List<Path> write(Document document, Path target, WriteOptions options) {
        try (XWPFDocument docx = new XWPFDocument()) {
            int imageCount = 0;
            List<Page> pages = document.pages();
            for (int i = 0; i < pages.size(); i++) {
                if (i > 0) {
                    docx.createParagraph().createRun().addBreak(BreakType.PAGE);
                }
                for (Block block : pages.get(i).blocks()) {
                    switch (block) {
                        case Paragraph p -> writeParagraph(docx, p);
                        case ImageBlock img -> writeImage(docx, img, ++imageCount);
                        case Table t -> writeTable(docx, t);
                    }
                }
            }
            try (OutputStream out = Files.newOutputStream(target)) {
                docx.write(out);
            }
            return List.of(target);
        } catch (IOException | InvalidFormatException e) {
            throw new ConversionException("Could not write Word file " + target + ": " + e.getMessage(), e);
        }
    }

    private static void writeParagraph(XWPFDocument docx, Paragraph p) {
        XWPFParagraph paragraph = docx.createParagraph();
        XWPFRun run = paragraph.createRun();
        run.setText(p.text());
        if (p.isHeading()) {
            // Style id lets Word (and our reader) recognise the heading;
            // direct formatting makes it look like one even without a style sheet.
            paragraph.setStyle("Heading1");
            run.setBold(true);
            run.setFontSize(HEADING_FONT_SIZE);
        }
    }

    private static void writeTable(XWPFDocument docx, Table table) {
        if (table.isEmpty()) {
            return;
        }
        int cols = table.columnCount();
        XWPFTable t = docx.createTable(table.rowCount(), cols);
        t.setWidth("100%");
        for (int r = 0; r < table.rowCount(); r++) {
            XWPFTableRow row = t.getRow(r);
            for (int c = 0; c < cols; c++) {
                XWPFParagraph p = row.getCell(c).getParagraphs().getFirst();
                XWPFRun run = p.createRun();
                run.setText(table.cell(r, c));
                if (table.header() && r == 0) {
                    run.setBold(true);
                    row.getCell(c).setColor("EEF3FB");
                }
            }
            if (table.header() && r == 0) {
                row.setRepeatHeader(true);
            }
        }
        docx.createParagraph(); // keep following text from sticking to the table
    }

    private static void writeImage(XWPFDocument docx, ImageBlock img, int index)
            throws IOException, InvalidFormatException {
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(img.image(), "png", png);

        double scale = Math.min(1.0, MAX_IMAGE_WIDTH_PT / img.width());
        int widthEmu = Units.toEMU(img.width() * scale);
        int heightEmu = Units.toEMU(img.height() * scale);

        XWPFRun run = docx.createParagraph().createRun();
        run.addPicture(new ByteArrayInputStream(png.toByteArray()),
                org.apache.poi.xwpf.usermodel.Document.PICTURE_TYPE_PNG,
                "image" + index + ".png", widthEmu, heightEmu);
    }
}

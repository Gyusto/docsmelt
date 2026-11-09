package com.docsmelt.io.office;

import com.docsmelt.io.ConversionException;
import com.docsmelt.io.DocumentWriter;
import com.docsmelt.io.WriteOptions;
import com.docsmelt.model.Block;
import com.docsmelt.model.Document;
import com.docsmelt.model.ImageBlock;
import com.docsmelt.model.Page;
import com.docsmelt.model.Paragraph;
import com.docsmelt.model.Table;
import org.apache.poi.sl.usermodel.PictureData;
import org.apache.poi.sl.usermodel.TableCell;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFPictureData;
import org.apache.poi.xslf.usermodel.XSLFPictureShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.xslf.usermodel.XSLFTableCell;
import org.apache.poi.xslf.usermodel.XSLFTableRow;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xslf.usermodel.XSLFTextParagraph;
import org.apache.poi.xslf.usermodel.XSLFTextRun;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xslf.usermodel.SlideLayout;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes a 16:9 .pptx. Pages that have a picture of themselves (PDF pages,
 * images, slides) become one full-slide picture each. Other content is laid
 * out as slides: headings become titles, paragraphs flow into text boxes
 * (continuing on new slides when full), and tables and images get their own
 * slides.
 */
public final class PresentationWriter implements DocumentWriter {

    private static final double W = 960;
    private static final double H = 540;
    private static final double MARGIN = 40;
    private static final double TITLE_H = 70;
    private static final double BODY_SIZE = 18;
    private static final int LINES_PER_SLIDE = 13;
    private static final int CHARS_PER_LINE = 80;
    private static final int TABLE_ROWS_PER_SLIDE = 12;
    private static final Color ACCENT = new Color(0x2F76EA);

    @Override
    public boolean wantsSnapshots() {
        return true;
    }

    @Override
    public List<Path> write(Document document, Path target, WriteOptions options) {
        try (XMLSlideShow show = new XMLSlideShow()) {
            show.setPageSize(new Dimension((int) W, (int) H));
            for (Page page : document.pages()) {
                if (page.snapshot().isPresent()) {
                    pictureSlide(show, page.snapshot().get());
                } else {
                    new Flow(show).page(page);
                }
            }
            if (show.getSlides().isEmpty()) {
                show.createSlide();
            }
            try (OutputStream out = Files.newOutputStream(target)) {
                show.write(out);
            }
            return List.of(target);
        } catch (IOException e) {
            throw new ConversionException("Could not write presentation " + target + ": " + e.getMessage(), e);
        }
    }

    private static void pictureSlide(XMLSlideShow show, BufferedImage image) throws IOException {
        XSLFSlide slide = show.createSlide();
        placePicture(show, slide, image, new Rectangle2D.Double(0, 0, W, H));
    }

    private static void placePicture(XMLSlideShow show, XSLFSlide slide, BufferedImage image, Rectangle2D box)
            throws IOException {
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(image, "png", png);
        XSLFPictureData data = show.addPicture(png.toByteArray(), PictureData.PictureType.PNG);
        XSLFPictureShape pic = slide.createPicture(data);
        double scale = Math.min(box.getWidth() / image.getWidth(), box.getHeight() / image.getHeight());
        double w = image.getWidth() * scale;
        double h = image.getHeight() * scale;
        pic.setAnchor(new Rectangle2D.Double(box.getX() + (box.getWidth() - w) / 2,
                box.getY() + (box.getHeight() - h) / 2, w, h));
    }

    /** Lays flowing content out as a sequence of slides. */
    private static final class Flow {
        private final XMLSlideShow show;
        private String title;
        private boolean titleShown = true;
        private XSLFTextBox body;
        private int lines;

        Flow(XMLSlideShow show) {
            this.show = show;
        }

        void page(Page page) throws IOException {
            for (Block block : page.blocks()) {
                switch (block) {
                    case Paragraph p when p.isHeading() -> {
                        flushTitle(); // two headings in a row: the first still gets a slide
                        title = p.text();
                        titleShown = false;
                        body = null; // a new heading starts a new slide
                    }
                    case Paragraph p -> text(p.text());
                    case ImageBlock img -> {
                        XSLFSlide slide = newSlide(title);
                        placePicture(show, slide, img.image(), contentBox());
                        body = null;
                    }
                    case Table t -> table(t);
                }
            }
            flushTitle(); // a heading with nothing after it still gets its slide
        }

        private void flushTitle() {
            if (!titleShown && title != null) {
                newSlide(title);
            }
        }

        private void text(String text) {
            int needed = Math.max(1, (int) Math.ceil(text.length() / (double) CHARS_PER_LINE));
            if (body == null || lines + needed > LINES_PER_SLIDE) {
                XSLFSlide slide = newSlide(body == null ? title : continued(title));
                body = slide.createTextBox();
                body.setAnchor(contentBox());
                body.setWordWrap(true);
                body.clearText();
                lines = 0;
            }
            XSLFTextParagraph p = body.addNewTextParagraph();
            p.setSpaceAfter(6.0);
            XSLFTextRun run = p.addNewTextRun();
            run.setText(text);
            run.setFontSize(BODY_SIZE);
            lines += needed;
        }

        private void table(Table table) {
            if (table.isEmpty()) {
                return;
            }
            int cols = table.columnCount();
            List<List<String>> rows = table.rows();
            int start = 0;
            List<String> header = table.header() ? rows.getFirst() : null;
            if (header != null) {
                start = 1;
            }
            do {
                int end = Math.min(rows.size(), start + TABLE_ROWS_PER_SLIDE);
                List<List<String>> chunk = new ArrayList<>();
                if (header != null) {
                    chunk.add(header);
                }
                chunk.addAll(rows.subList(start, end));
                tableSlide(chunk, cols, header != null, start == (header != null ? 1 : 0) ? title : continued(title));
                start = end;
            } while (start < rows.size());
            body = null;
        }

        private void tableSlide(List<List<String>> rows, int cols, boolean header, String slideTitle) {
            XSLFSlide slide = newSlide(slideTitle);
            XSLFTable t = slide.createTable();
            Rectangle2D box = contentBox();
            double colWidth = box.getWidth() / cols;
            double fontSize = cols > 8 ? 10 : cols > 5 ? 12 : 14;
            for (int r = 0; r < rows.size(); r++) {
                XSLFTableRow row = t.addRow();
                row.setHeight(fontSize * 2);
                for (int c = 0; c < cols; c++) {
                    XSLFTableCell cell = row.addCell();
                    List<String> values = rows.get(r);
                    XSLFTextRun run = cell.setText(c < values.size() ? values.get(c) : "");
                    run.setFontSize(fontSize);
                    boolean isHeader = header && r == 0;
                    if (isHeader) {
                        run.setBold(true);
                        cell.setFillColor(new Color(0xEEF3FB));
                    }
                    for (TableCell.BorderEdge edge : TableCell.BorderEdge.values()) {
                        cell.setBorderColor(edge, new Color(0xC9D3E0));
                        cell.setBorderWidth(edge, 0.75);
                    }
                }
            }
            for (int c = 0; c < cols; c++) {
                t.setColumnWidth(c, colWidth);
            }
            t.setAnchor(new Rectangle2D.Double(box.getX(), box.getY(), box.getWidth(), rows.size() * fontSize * 2));
        }

        private XSLFSlide newSlide(String slideTitle) {
            titleShown = true;
            if (slideTitle == null || slideTitle.isBlank()) {
                return show.createSlide(show.getSlideMasters().getFirst().getLayout(SlideLayout.BLANK));
            }
            // A real title placeholder: PowerPoint shows it in the outline and screen readers announce it.
            XSLFSlide slide = show.createSlide(show.getSlideMasters().getFirst().getLayout(SlideLayout.TITLE_ONLY));
            XSLFTextShape box = slide.getPlaceholder(0);
            box.setAnchor(new Rectangle2D.Double(MARGIN, MARGIN * 0.6, W - 2 * MARGIN, TITLE_H));
            box.clearText();
            XSLFTextRun run = box.addNewTextParagraph().addNewTextRun();
            run.setText(slideTitle);
            run.setFontSize(30.0);
            run.setBold(true);
            run.setFontColor(ACCENT);
            return slide;
        }

        private Rectangle2D contentBox() {
            double top = MARGIN * 0.6 + TITLE_H + 10;
            return new Rectangle2D.Double(MARGIN, top, W - 2 * MARGIN, H - top - MARGIN);
        }

        private static String continued(String title) {
            return title == null ? null : title + " (cont.)";
        }
    }
}

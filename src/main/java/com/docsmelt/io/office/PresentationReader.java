package com.docsmelt.io.office;

import com.docsmelt.io.ConversionException;
import com.docsmelt.io.DocumentReader;
import com.docsmelt.io.ReadOptions;
import com.docsmelt.model.Document;
import com.docsmelt.model.ImageBlock;
import com.docsmelt.model.Page;
import com.docsmelt.model.Paragraph;
import com.docsmelt.model.Table;
import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.sl.usermodel.Placeholder;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFGroupShape;
import org.apache.poi.xslf.usermodel.XSLFPictureShape;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.xslf.usermodel.XSLFTableCell;
import org.apache.poi.xslf.usermodel.XSLFTableRow;
import org.apache.poi.xslf.usermodel.XSLFTextParagraph;
import org.apache.poi.xslf.usermodel.XSLFTextShape;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Reads PowerPoint (.pptx). Each slide becomes a fixed-layout page:
 * <ul>
 *   <li>a rendered <em>snapshot</em> of the slide (used for PDF and image output),
 *       when the writer asks for snapshots;</li>
 *   <li>its title, text, pictures and tables as blocks, in reading order
 *       (used for Word, text and Excel output).</li>
 * </ul>
 */
public final class PresentationReader implements DocumentReader {

    @Override
    public Document read(Path source, ReadOptions options) {
        try (InputStream in = Files.newInputStream(source);
             XMLSlideShow show = new XMLSlideShow(in)) {
            Dimension size = show.getPageSize();
            Page.Size pageSize = new Page.Size((float) size.getWidth(), (float) size.getHeight());
            Document document = new Document();
            List<XSLFSlide> slides = show.getSlides();
            for (int i = 0; i < slides.size(); i++) {
                if (!options.pages().includes(i + 1)) {
                    continue;
                }
                XSLFSlide slide = slides.get(i);
                Page page = document.newPage().withFixedSize(pageSize);
                readShapes(slide.getShapes(), page);
                if (options.renderSnapshots()) {
                    BufferedImage image = render(slide, size, options.dpi());
                    if (image != null) {
                        page.withSnapshot(image);
                    }
                }
            }
            options.pages().requireAny(document.pages().size(), slides.size());
            return document;
        } catch (EncryptedDocumentException e) {
            throw new ConversionException("This presentation is password-protected", e);
        } catch (IOException | RuntimeException e) {
            if (e instanceof ConversionException ce) {
                throw ce;
            }
            throw new ConversionException("Could not read presentation " + source.getFileName() + ": " + e.getMessage(), e);
        }
    }

    /** Shapes in reading order: top to bottom, then left to right. */
    private static void readShapes(List<XSLFShape> shapes, Page page) throws IOException {
        List<XSLFShape> ordered = new ArrayList<>(shapes);
        ordered.sort(Comparator.comparingDouble((XSLFShape s) -> anchor(s).getY())
                .thenComparingDouble(s -> anchor(s).getX()));
        for (XSLFShape shape : ordered) {
            switch (shape) {
                case XSLFGroupShape group -> readShapes(group.getShapes(), page);
                case XSLFTable table -> page.add(table(table));
                case XSLFPictureShape picture -> {
                    BufferedImage img = ImageIO.read(new ByteArrayInputStream(picture.getPictureData().getData()));
                    if (img != null) {
                        page.add(new ImageBlock(img));
                    }
                }
                case XSLFTextShape text -> readText(text, page);
                default -> { /* lines, connectors, charts: nothing to extract */ }
            }
        }
    }

    private static void readText(XSLFTextShape shape, Page page) {
        Placeholder ph = shape.getPlaceholder();
        boolean title = ph == Placeholder.TITLE || ph == Placeholder.CENTERED_TITLE;
        if (ph == Placeholder.SLIDE_NUMBER || ph == Placeholder.DATETIME || ph == Placeholder.FOOTER) {
            return;
        }
        if (title) {
            String text = shape.getText().strip().replaceAll("\\s+", " ");
            if (!text.isEmpty()) {
                page.add(Paragraph.heading(text));
            }
            return;
        }
        for (XSLFTextParagraph p : shape.getTextParagraphs()) {
            String text = p.getText().strip();
            if (!text.isEmpty()) {
                page.add(Paragraph.body(p.isBullet() ? "• " + text : text));
            }
        }
    }

    private static Table table(XSLFTable table) {
        List<List<String>> rows = new ArrayList<>();
        for (XSLFTableRow row : table.getRows()) {
            rows.add(row.getCells().stream().map(XSLFTableCell::getText).map(String::strip).toList());
        }
        return new Table(rows, rows.size() > 1);
    }

    private static Rectangle2D anchor(XSLFShape shape) {
        try {
            Rectangle2D a = shape.getAnchor();
            return a == null ? new Rectangle2D.Double() : a;
        } catch (RuntimeException e) {
            return new Rectangle2D.Double();
        }
    }

    /** @return the slide as an image, or null if POI can't draw it */
    private static BufferedImage render(XSLFSlide slide, Dimension size, int dpi) {
        double scale = dpi / 72.0;
        int w = (int) Math.round(size.getWidth() * scale);
        int h = (int) Math.round(size.getHeight() * scale);
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            g.setPaint(Color.WHITE);
            g.fillRect(0, 0, w, h);
            g.scale(scale, scale);
            slide.draw(g);
            return img;
        } catch (RuntimeException e) {
            return null; // fall back to the extracted blocks
        } finally {
            g.dispose();
        }
    }
}

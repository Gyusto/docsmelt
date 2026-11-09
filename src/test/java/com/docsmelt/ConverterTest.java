package com.docsmelt;

import com.docsmelt.core.Converter;
import com.docsmelt.format.Format;
import com.docsmelt.format.UnsupportedFormatException;
import com.docsmelt.io.docx.DocxDocumentReader;
import com.docsmelt.io.docx.DocxDocumentWriter;
import com.docsmelt.io.pdf.PdfDocumentReader;
import com.docsmelt.io.pdf.PdfDocumentWriter;
import com.docsmelt.io.text.TextDocumentReader;
import com.docsmelt.model.Document;
import com.docsmelt.model.ImageBlock;
import com.docsmelt.model.Paragraph;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConverterTest {

    @TempDir
    Path dir;

    private final Converter converter = new Converter();

    private static BufferedImage sampleImage() {
        BufferedImage img = new BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, 100, 100);
        g.setColor(Color.BLUE);
        g.fillRect(100, 0, 100, 100);
        g.dispose();
        return img;
    }

    private static Document sampleDocument() {
        Document doc = new Document();
        doc.newPage()
                .add(Paragraph.heading("Quarterly Report"))
                .add(Paragraph.body("Revenue grew steadily. ".repeat(40).strip()))
                .add(new ImageBlock(sampleImage()));
        doc.newPage().add(Paragraph.body("Second page text."));
        return doc;
    }

    @Test
    void pdfRoundTripKeepsTextAndPages() {
        Path pdf = dir.resolve("out.pdf");
        new PdfDocumentWriter().write(sampleDocument(), pdf);

        Document back = new PdfDocumentReader().read(pdf);
        assertEquals(2, back.pages().size());
        assertTrue(back.plainText().contains("Quarterly Report"));
        assertTrue(back.plainText().contains("Second page text."));
        assertTrue(back.blocks().anyMatch(ImageBlock.class::isInstance), "image should survive");
    }

    @Test
    void docxRoundTripKeepsHeadingsAndImages() {
        Path docx = dir.resolve("out.docx");
        new DocxDocumentWriter().write(sampleDocument(), docx);

        Document back = new DocxDocumentReader().read(docx);
        Paragraph first = (Paragraph) back.blocks().findFirst().orElseThrow();
        assertTrue(first.isHeading());
        assertEquals("Quarterly Report", first.text());
        assertTrue(back.blocks().anyMatch(ImageBlock.class::isInstance));
    }

    @Test
    void docxToPdfToTxt() throws Exception {
        Path docx = dir.resolve("in.docx");
        new DocxDocumentWriter().write(sampleDocument(), docx);

        converter.convert(docx, dir.resolve("mid.pdf"));
        converter.convert(dir.resolve("mid.pdf"), dir.resolve("out.txt"));

        String text = Files.readString(dir.resolve("out.txt"));
        assertTrue(text.contains("Quarterly Report"));
        assertTrue(text.contains("Second page text."));
    }

    @Test
    void multiPagePdfToPngWritesOneImagePerPage() throws Exception {
        Path pdf = dir.resolve("doc.pdf");
        new PdfDocumentWriter().write(sampleDocument(), pdf);

        List<Path> out = converter.convert(pdf, dir.resolve("page.png"));
        assertEquals(List.of(dir.resolve("page-1.png"), dir.resolve("page-2.png")), out);
        BufferedImage firstPage = ImageIO.read(out.getFirst().toFile());
        assertTrue(firstPage.getWidth() > 1000, "rendered at 150 dpi");
    }

    @Test
    void imageToPdfAndToJpeg() throws Exception {
        Path png = dir.resolve("photo.png");
        ImageIO.write(sampleImage(), "png", png.toFile());

        converter.convert(png, dir.resolve("photo.pdf"));
        Document pdf = new PdfDocumentReader().read(dir.resolve("photo.pdf"));
        assertTrue(pdf.blocks().anyMatch(ImageBlock.class::isInstance));

        converter.convert(png, dir.resolve("photo.jpg"));
        BufferedImage jpg = ImageIO.read(dir.resolve("photo.jpg").toFile());
        assertEquals(200, jpg.getWidth());
        assertEquals(100, jpg.getHeight());
    }

    @Test
    void docxToPngRasterizesText() throws Exception {
        Path docx = dir.resolve("in.docx");
        new DocxDocumentWriter().write(sampleDocument(), docx);

        List<Path> out = converter.convert(docx, dir.resolve("out.png"));
        assertFalse(out.isEmpty());
        assertNotNull(ImageIO.read(out.getFirst().toFile()));
    }

    @Test
    void textReaderUnderstandsHeadingsAndParagraphs() throws Exception {
        Path txt = dir.resolve("in.txt");
        Files.writeString(txt, "# Title\n\nline one\nline two\n\nsecond para\n");

        Document doc = new TextDocumentReader().read(txt);
        List<Paragraph> paras = doc.blocks().map(Paragraph.class::cast).toList();
        assertEquals(List.of(Paragraph.heading("Title"), Paragraph.body("line one line two"),
                Paragraph.body("second para")), paras);
    }

    @Test
    void unsupportedExtensionIsRejected() throws Exception {
        Path in = dir.resolve("in.txt");
        Files.writeString(in, "hi");
        assertThrows(UnsupportedFormatException.class, () -> converter.convert(in, dir.resolve("out.xyz")));
        assertEquals(Format.JPEG, Format.fromPath(Path.of("a.JPEG")));
    }
}

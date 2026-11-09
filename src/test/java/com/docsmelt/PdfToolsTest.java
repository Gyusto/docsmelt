package com.docsmelt;

import com.docsmelt.cli.Main;
import com.docsmelt.core.ConversionOptions;
import com.docsmelt.core.Converter;
import com.docsmelt.io.ConversionException;
import com.docsmelt.io.pdf.PdfDocumentReader;
import com.docsmelt.io.pdf.PdfDocumentWriter;
import com.docsmelt.model.Document;
import com.docsmelt.model.Paragraph;
import com.docsmelt.pdf.PdfMerger;
import com.docsmelt.pdf.PdfSplitter;
import com.docsmelt.pdf.SplitStrategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PdfToolsTest {

    @TempDir
    Path dir;

    private Path pdf(String name, String prefix, int pages) {
        Document doc = new Document();
        for (int i = 1; i <= pages; i++) {
            doc.newPage().add(Paragraph.body(prefix + " page " + i));
        }
        Path out = dir.resolve(name);
        new PdfDocumentWriter().write(doc, out);
        return out;
    }

    private static Document read(Path p) {
        return new PdfDocumentReader().read(p);
    }

    @Test
    void mergeKeepsOrderAndMixesFormats() throws Exception {
        Path a = pdf("a.pdf", "A", 2);
        Path b = pdf("b.pdf", "B", 3);
        Path txt = dir.resolve("c.txt");
        Files.writeString(txt, "C text");
        Path png = dir.resolve("d.png");
        ImageIO.write(new BufferedImage(50, 50, BufferedImage.TYPE_INT_RGB), "png", png.toFile());

        Path out = dir.resolve("merged/all.pdf");
        int pages = new PdfMerger(new Converter()).merge(List.of(b, a, txt, png), out, ConversionOptions.DEFAULT);

        assertEquals(7, pages);
        Document merged = read(out);
        assertEquals(7, merged.pages().size());
        String text = merged.plainText();
        assertTrue(text.indexOf("B page 1") < text.indexOf("A page 1"), "order follows the arguments");
        assertTrue(text.contains("C text"));
    }

    @Test
    void mergeNeedsTwoInputsAndWontOverwriteThem() {
        Path a = pdf("a.pdf", "A", 1);
        PdfMerger merger = new PdfMerger(new Converter());
        assertThrows(ConversionException.class, () -> merger.merge(List.of(a), dir.resolve("o.pdf"), ConversionOptions.DEFAULT));
        assertThrows(ConversionException.class, () -> merger.merge(List.of(a, pdf("b.pdf", "B", 1)), a, ConversionOptions.DEFAULT));
    }

    @Test
    void splitEveryPageAndEveryN() {
        Path book = pdf("book.pdf", "X", 5);

        List<Path> single = new PdfSplitter().split(book, new SplitStrategy.EveryNPages(1), dir.resolve("p"));
        assertEquals(5, single.size());
        assertEquals(dir.resolve("p/book-3.pdf"), single.get(2));
        assertTrue(read(single.get(2)).plainText().contains("X page 3"));

        List<Path> twos = new PdfSplitter().split(book, new SplitStrategy.EveryNPages(2), dir.resolve("t"));
        assertEquals(List.of(2, 2, 1), twos.stream().map(p -> read(p).pages().size()).toList());
    }

    @Test
    void splitByRanges() {
        Path book = pdf("book.pdf", "X", 6);
        List<Path> parts = new PdfSplitter().split(book, SplitStrategy.ByRanges.parse("1-2,5,4-"), dir.resolve("r"));
        assertEquals(List.of(2, 1, 3), parts.stream().map(p -> read(p).pages().size()).toList());

        assertThrows(ConversionException.class,
                () -> new PdfSplitter().split(book, SplitStrategy.ByRanges.parse("9-10"), dir));
    }

    @Test
    void cliMergeAndSplit() {
        Path a = pdf("a.pdf", "A", 2);
        Path b = pdf("b.pdf", "B", 2);
        Path all = dir.resolve("all.pdf");

        assertEquals(0, Main.run("merge", a.toString(), b.toString(), "-o", all.toString()));
        assertEquals(4, read(all).pages().size());

        assertEquals(0, Main.run("split", all.toString(), "--every", "3", "-o", dir.resolve("s").toString()));
        assertTrue(Files.exists(dir.resolve("s/all-2.pdf")));

        assertEquals(2, Main.run("merge", a.toString())); // missing -o and second file
        assertEquals(2, Main.run("split", all.toString(), "--every", "2", "--ranges", "1"));
        assertEquals(2, Main.run("split", all.toString(), "--every", "0"));
    }
}

package com.docsmelt.pdf;

import com.docsmelt.format.Format;
import com.docsmelt.io.ConversionException;
import com.docsmelt.io.PageRange;
import com.docsmelt.io.pdf.FontChain;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;

import java.awt.Color;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Stamps page numbers onto a PDF, e.g. "Page 3 of 10". Positions are as the
 * page is <em>displayed</em>, so rotated pages are numbered the right way up.
 */
public final class PageNumberer {

    public enum Position {
        BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT, TOP_LEFT, TOP_CENTER, TOP_RIGHT;

        public static Position parse(String s) {
            try {
                return valueOf(s.strip().toUpperCase(Locale.ROOT).replace('-', '_'));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Position must be one of bottom-left, bottom-center, bottom-right, "
                        + "top-left, top-center, top-right; got '" + s + "'");
            }
        }

        boolean top() {
            return name().startsWith("TOP");
        }
    }

    /**
     * @param template text with {n} (this page's number) and optionally {total}
     * @param start    number for the first page of the document
     * @param pages    which pages get a number (others are skipped but still counted)
     */
    public record Options(Position position, String template, int start, PageRange pages, float fontSize) {
        public static Options defaults() {
            return new Options(Position.BOTTOM_CENTER, "{n}", 1, PageRange.ALL, 10f);
        }

        public Options {
            if (!template.contains("{n}")) {
                throw new IllegalArgumentException("The format must contain {n}, e.g. \"Page {n} of {total}\"");
            }
            if (template.length() > 60) {
                throw new IllegalArgumentException("The format is too long (max 60 characters)");
            }
            if (fontSize < 6 || fontSize > 36) {
                throw new IllegalArgumentException("Font size must be between 6 and 36");
            }
            if (start < 0 || start > 100_000) {
                throw new IllegalArgumentException("Start number must be between 0 and 100000");
            }
        }
    }

    private static final float MARGIN = 28f; // ~1 cm from the edge
    private static final Color INK = new Color(0x3A4250);

    /** @return number of pages that got a number */
    public int apply(Path source, Path target, Options options) {
        if (Format.fromPath(source) != Format.PDF) {
            throw new ConversionException("Only PDF files can be numbered: " + source.getFileName());
        }
        if (source.toAbsolutePath().normalize().equals(target.toAbsolutePath().normalize())) {
            throw new ConversionException("Output would overwrite the input: " + source);
        }
        try (PDDocument pdf = PdfMerger.load(source, source)) {
            FontChain font = FontChain.regular(pdf, List.of());
            int total = pdf.getNumberOfPages();
            int lastNumber = options.start() + total - 1;
            int stamped = 0;
            for (int i = 0; i < total; i++) {
                if (!options.pages().includes(i + 1)) {
                    continue;
                }
                String label = options.template()
                        .replace("{n}", String.valueOf(options.start() + i))
                        .replace("{total}", String.valueOf(lastNumber));
                stamp(pdf, pdf.getPage(i), font, label, options);
                stamped++;
            }
            options.pages().requireAny(stamped, total);
            Files.createDirectories(target.toAbsolutePath().getParent());
            pdf.save(target.toFile());
            return stamped;
        } catch (IOException e) {
            throw new ConversionException("Could not number " + source.getFileName() + ": " + e.getMessage(), e);
        }
    }

    private static void stamp(PDDocument pdf, PDPage page, FontChain font, String label, Options o) throws IOException {
        PageGeometry geo = new PageGeometry(page);
        float textWidth = font.width(label, o.fontSize());
        float u = switch (o.position()) {
            case BOTTOM_LEFT, TOP_LEFT -> MARGIN;
            case BOTTOM_CENTER, TOP_CENTER -> (geo.width() - textWidth) / 2;
            case BOTTOM_RIGHT, TOP_RIGHT -> geo.width() - MARGIN - textWidth;
        };
        float v = o.position().top() ? geo.height() - MARGIN - o.fontSize() * 0.75f : MARGIN;

        // Append after existing content, in a clean graphics state.
        try (PDPageContentStream cs = new PDPageContentStream(pdf, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
            cs.setNonStrokingColor(INK);
            cs.beginText();
            cs.setTextMatrix(geo.place(u, v, 1, 1));
            for (FontChain.Run run : font.runs(label)) {
                cs.setFont(run.font(), o.fontSize());
                cs.showText(run.text());
            }
            cs.endText();
        }
    }
}

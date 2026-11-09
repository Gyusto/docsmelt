package com.filekit.io.pdf;

import com.filekit.io.ConversionException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * An ordered list of embedded fonts with per-character fallback: each character
 * is drawn with the first font that has a glyph for it, so a line can mix
 * Latin (DejaVu) with e.g. CJK (a user-supplied fallback font).
 *
 * <p>Fonts are embedded as subsets, so only the glyphs actually used end up in the PDF.
 */
final class FontChain {

    /** Extra fallback fonts, separated by the platform path separator (':' on Linux). */
    static final String FONTS_ENV = "FILEKIT_FONTS";

    record Run(PDFont font, String text) {
    }

    private final List<PDFont> fonts;
    private final Map<Integer, PDFont> fontForCodePoint = new HashMap<>();

    private FontChain(List<PDFont> fonts) {
        this.fonts = fonts;
    }

    static FontChain regular(PDDocument pdf, List<Path> extraFonts) {
        return load(pdf, "/fonts/DejaVuSans.ttf", extraFonts);
    }

    static FontChain bold(PDDocument pdf, List<Path> extraFonts) {
        return load(pdf, "/fonts/DejaVuSans-Bold.ttf", extraFonts);
    }

    private static FontChain load(PDDocument pdf, String bundledResource, List<Path> extraFonts) {
        List<PDFont> fonts = new ArrayList<>();
        try (InputStream in = FontChain.class.getResourceAsStream(bundledResource)) {
            if (in == null) {
                throw new ConversionException("Bundled font missing from the jar: " + bundledResource);
            }
            fonts.add(PDType0Font.load(pdf, in));
        } catch (IOException e) {
            throw new ConversionException("Could not load bundled font " + bundledResource, e);
        }
        for (Path path : allExtraFonts(extraFonts)) {
            fonts.add(loadFile(pdf, path));
        }
        return new FontChain(fonts);
    }

    private static List<Path> allExtraFonts(List<Path> explicit) {
        List<Path> all = new ArrayList<>(explicit);
        String env = System.getenv(FONTS_ENV);
        if (env != null) {
            for (String p : env.split(File.pathSeparator)) {
                if (!p.isBlank()) {
                    all.add(Path.of(p.strip()));
                }
            }
        }
        return all;
    }

    private static PDFont loadFile(PDDocument pdf, Path path) {
        if (!Files.isRegularFile(path)) {
            throw new ConversionException("Font file not found: " + path);
        }
        try {
            return PDType0Font.load(pdf, path.toFile());
        } catch (IOException | RuntimeException e) {
            throw new ConversionException("Cannot use font " + path + " (only TrueType .ttf fonts with "
                    + "glyf outlines are supported): " + e.getMessage(), e);
        }
    }

    /** Splits text into consecutive runs sharing a font. Unsupported characters become '?'. */
    List<Run> runs(String text) {
        List<Run> runs = new ArrayList<>();
        PDFont current = null;
        StringBuilder buf = new StringBuilder();
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            PDFont font = fontFor(cp);
            String ch = font == null ? "?" : new String(Character.toChars(cp));
            if (font == null) {
                font = fonts.getFirst();
            }
            if (font != current && !buf.isEmpty()) {
                runs.add(new Run(current, buf.toString()));
                buf.setLength(0);
            }
            current = font;
            buf.append(ch);
        }
        if (!buf.isEmpty()) {
            runs.add(new Run(current, buf.toString()));
        }
        return runs;
    }

    float width(String text, float fontSize) throws IOException {
        float total = 0;
        for (Run run : runs(text)) {
            total += run.font().getStringWidth(run.text()) / 1000f * fontSize;
        }
        return total;
    }

    private PDFont fontFor(int codePoint) {
        return fontForCodePoint.computeIfAbsent(codePoint, cp -> {
            String s = new String(Character.toChars(cp));
            for (PDFont font : fonts) {
                try {
                    font.encode(s);
                    return font;
                } catch (IOException | IllegalArgumentException missingGlyph) {
                    // try the next font
                }
            }
            return null;
        });
    }
}

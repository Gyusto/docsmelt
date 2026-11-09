package com.docsmelt.pdf;

import com.docsmelt.format.Format;
import com.docsmelt.io.ConversionException;
import com.docsmelt.io.PageRange;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Saves the pictures embedded in a PDF. JPEG photos are copied byte for byte
 * (no quality loss); everything else is saved as PNG. An image used on
 * several pages is saved once.
 */
public final class PdfImageExtractor {

    /** Logos, bullets and spacer pixels below this size are skipped. */
    private static final int MIN_SIDE = 24;

    public List<Path> extract(Path source, Path outputDir, PageRange pages) {
        if (Format.fromPath(source) != Format.PDF) {
            throw new ConversionException("Images can only be extracted from PDFs: " + source.getFileName());
        }
        String stem = source.getFileName().toString().replaceFirst("(?i)\\.pdf$", "");
        try (PDDocument pdf = PdfMerger.load(source, source)) {
            Files.createDirectories(outputDir);
            Run run = new Run(outputDir, stem);
            int total = pdf.getNumberOfPages();
            int selected = 0;
            for (int i = 0; i < total; i++) {
                if (pages.includes(i + 1)) {
                    selected++;
                    run.page = i + 1;
                    run.counter = 0;
                    run.scan(pdf.getPage(i).getResources());
                }
            }
            pages.requireAny(selected, total);
            if (run.written.isEmpty()) {
                throw new ConversionException("No images found in " + source.getFileName()
                        + (pages.isAll() ? "" : " on the selected pages"));
            }
            return run.written;
        } catch (IOException e) {
            throw new ConversionException("Could not extract images: " + e.getMessage(), e);
        }
    }

    private static final class Run {
        final Path dir;
        final String stem;
        final List<Path> written = new ArrayList<>();
        final Set<COSBase> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        int page;
        int counter;

        Run(Path dir, String stem) {
            this.dir = dir;
            this.stem = stem;
        }

        void scan(PDResources resources) throws IOException {
            if (resources == null) {
                return;
            }
            for (COSName name : resources.getXObjectNames()) {
                PDXObject x;
                try {
                    x = resources.getXObject(name);
                } catch (IOException e) {
                    continue;
                }
                if (x == null || !seen.add(x.getCOSObject())) {
                    continue;
                }
                if (x instanceof PDImageXObject image) {
                    save(image);
                } else if (x instanceof PDFormXObject form) {
                    scan(form.getResources());
                }
            }
        }

        private void save(PDImageXObject image) {
            if (image.getWidth() < MIN_SIDE || image.getHeight() < MIN_SIDE) {
                return;
            }
            String base = String.format("%s-page%d-%d", stem, page, ++counter);
            try {
                if ("jpg".equals(image.getSuffix()) && image.getMask() == null && image.getSoftMask() == null) {
                    Path out = dir.resolve(base + ".jpg");
                    try (InputStream raw = image.createInputStream(List.of(COSName.DCT_DECODE.getName()))) {
                        Files.copy(raw, out);
                    }
                    written.add(out);
                } else {
                    Path out = dir.resolve(base + ".png");
                    if (ImageIO.write(image.getImage(), "png", out.toFile())) {
                        written.add(out);
                    }
                }
            } catch (IOException | RuntimeException e) {
                counter--; // undecodable image (exotic encoding): skip it
            }
        }
    }
}

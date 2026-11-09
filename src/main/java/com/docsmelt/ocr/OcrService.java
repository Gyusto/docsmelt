package com.docsmelt.ocr;

import com.docsmelt.format.Format;
import com.docsmelt.io.ConversionException;
import com.docsmelt.io.PageRange;
import com.docsmelt.io.docx.DocxDocumentWriter;
import com.docsmelt.io.text.TextDocumentReader;
import com.docsmelt.model.Document;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Turns scanned PDFs and photos into searchable PDFs, plain text or Word.
 * PDF pages are rendered to images first, then handed to an {@link OcrEngine}.
 * The output type follows the target's extension: .pdf, .txt or .docx.
 */
public final class OcrService {

    public record Options(String languages, int dpi, PageRange pages, int maxPages) {
        public static Options defaults() {
            return new Options("eng", 300, PageRange.ALL, 0);
        }

        public Options {
            if (dpi < 100 || dpi > 600) {
                throw new IllegalArgumentException("OCR dpi must be between 100 and 600");
            }
        }
    }

    /** "eng", "eng+swa": letters/underscores joined by '+'. Also blocks argument injection. */
    private static final Pattern LANG_SPEC = Pattern.compile("[A-Za-z_]+(\\+[A-Za-z_]+)*");

    private final OcrEngine engine;

    public OcrService(OcrEngine engine) {
        this.engine = engine;
    }

    public OcrEngine engine() {
        return engine;
    }

    public Path recognize(Path source, Path target, Options options) {
        if (!engine.isAvailable()) {
            throw new ConversionException("OCR is not available on this machine (Tesseract is not installed)");
        }
        validateLanguages(options.languages());
        Format in = Format.fromPath(source);
        Format out = Format.fromPath(target);
        if (in != Format.PDF && !in.isImage()) {
            throw new ConversionException("OCR works on PDFs and images, not ." + in.primaryExtension());
        }
        if (out != Format.PDF && out != Format.TXT && out != Format.DOCX) {
            throw new ConversionException("OCR can produce .pdf, .txt or .docx, not ." + out.primaryExtension());
        }

        Path work = null;
        try {
            work = Files.createTempDirectory("docsmelt-ocr");
            List<Path> images = in == Format.PDF ? renderPdf(source, work, options) : List.of(toPng(source, work));
            Path base = work.resolve("result");
            Set<OcrEngine.Output> wanted = out == Format.PDF
                    ? Set.of(OcrEngine.Output.SEARCHABLE_PDF) : Set.of(OcrEngine.Output.TEXT);
            engine.recognize(images, options.languages(), options.dpi(), base, wanted);

            Files.createDirectories(target.toAbsolutePath().getParent());
            switch (out) {
                case PDF -> Files.copy(produced(base, "pdf"), target, StandardCopyOption.REPLACE_EXISTING);
                case TXT -> Files.copy(produced(base, "txt"), target, StandardCopyOption.REPLACE_EXISTING);
                default -> {
                    Document doc = new TextDocumentReader().read(produced(base, "txt"));
                    new DocxDocumentWriter().write(doc, target);
                }
            }
            return target;
        } catch (IOException e) {
            throw new ConversionException("OCR failed: " + e.getMessage(), e);
        } finally {
            deleteQuietly(work);
        }
    }

    private void validateLanguages(String spec) {
        if (spec == null || !LANG_SPEC.matcher(spec).matches()) {
            throw new IllegalArgumentException("Invalid OCR language '" + spec + "' (use e.g. eng or eng+swa)");
        }
        List<String> installed = engine.languages();
        for (String lang : spec.split("\\+")) {
            if (!installed.contains(lang)) {
                throw new ConversionException("OCR language '" + lang + "' is not installed (available: "
                        + String.join(", ", installed) + ")");
            }
        }
    }

    private static List<Path> renderPdf(Path source, Path work, Options options) throws IOException {
        try (PDDocument pdf = org.apache.pdfbox.Loader.loadPDF(source.toFile())) {
            int total = pdf.getNumberOfPages();
            List<Integer> selected = new ArrayList<>();
            for (int i = 1; i <= total; i++) {
                if (options.pages().includes(i)) {
                    selected.add(i);
                }
            }
            options.pages().requireAny(selected.size(), total);
            if (options.maxPages() > 0 && selected.size() > options.maxPages()) {
                throw new ConversionException("OCR is limited to " + options.maxPages() + " pages here ("
                        + selected.size() + " selected); choose a page range");
            }
            PDFRenderer renderer = new PDFRenderer(pdf);
            List<Path> images = new ArrayList<>();
            for (int page : selected) {
                // Grayscale is plenty for OCR and much faster to process.
                BufferedImage img = renderer.renderImageWithDPI(page - 1, options.dpi(), ImageType.GRAY);
                Path png = work.resolve(String.format("page-%04d.png", page));
                ImageIO.write(img, "png", png.toFile());
                images.add(png);
            }
            return images;
        } catch (IOException e) {
            throw new ConversionException("Could not read PDF " + source.getFileName() + ": " + e.getMessage(), e);
        }
    }

    /** Re-encode images as PNG so the engine never sees an unusual format. */
    private static Path toPng(Path image, Path work) throws IOException {
        BufferedImage img = com.docsmelt.io.image.ImageLoader.read(image);
        if (img == null) {
            throw new ConversionException("Not a readable image: " + image.getFileName());
        }
        Path png = work.resolve("page-0001.png");
        ImageIO.write(img, "png", png.toFile());
        return png;
    }

    private static Path produced(Path base, String ext) {
        Path file = base.resolveSibling(base.getFileName() + "." + ext);
        if (!Files.isRegularFile(file)) {
            throw new ConversionException("OCR produced no ." + ext + " output");
        }
        return file;
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
            // temp files are cleaned eventually
        }
    }
}

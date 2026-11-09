package com.docsmelt.pdf;

import com.docsmelt.format.Format;
import com.docsmelt.io.ConversionException;
import com.docsmelt.io.ProcessRunner;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Converts PDFs to PDF/A-2b, the archiving standard (fonts embedded, colours
 * defined, no external dependencies), using Ghostscript. The binary can be
 * overridden with {@code DOCSMELT_GHOSTSCRIPT}, the sRGB profile with
 * {@code DOCSMELT_ICC}.
 */
public final class PdfAConverter {

    private static final Duration TIMEOUT = Duration.ofMinutes(10);
    private final String binary;
    private volatile Boolean available;

    public PdfAConverter() {
        this(System.getenv().getOrDefault("DOCSMELT_GHOSTSCRIPT", "gs"));
    }

    public PdfAConverter(String binary) {
        this.binary = binary;
    }

    public boolean isAvailable() {
        Boolean a = available;
        if (a == null) {
            try {
                ProcessRunner.run(List.of(binary, "--version"), Duration.ofSeconds(15), "Ghostscript");
                a = iccProfile() != null;
            } catch (ConversionException e) {
                a = false;
            }
            available = a;
        }
        return a;
    }

    public void convert(Path source, Path target) {
        if (Format.fromPath(source) != Format.PDF) {
            throw new ConversionException("Only PDF files can be converted to PDF/A: " + source.getFileName());
        }
        if (!isAvailable()) {
            throw new ConversionException("PDF/A conversion is not available here (Ghostscript is not installed)");
        }
        // Encrypted input can't become PDF/A; give a clear message instead of a Ghostscript error.
        if (com.docsmelt.io.Limits.pdfPages(source) < 0) {
            throw new ConversionException(source.getFileName() + " is password-protected; unlock it first");
        }
        Path icc = iccProfile();
        Path work = null;
        try {
            work = Files.createTempDirectory("docsmelt-pdfa");
            Path def = work.resolve("PDFA_def.ps");
            Files.writeString(def, pdfaDefinition(icc));
            Files.createDirectories(target.toAbsolutePath().getParent());
            List<String> cmd = new ArrayList<>(List.of(binary,
                    "-dPDFA=2", "-dBATCH", "-dNOPAUSE", "-dSAFER", "-dNOOUTERSAVE", "-dQUIET",
                    "--permit-file-read=" + icc.toAbsolutePath(),
                    "--permit-file-read=" + work.toAbsolutePath() + "/",
                    "-sColorConversionStrategy=RGB", "-sDEVICE=pdfwrite", "-dPDFACompatibilityPolicy=1",
                    "-sOutputFile=" + target.toAbsolutePath(),
                    def.toAbsolutePath().toString(), source.toAbsolutePath().toString()));
            ProcessRunner.run(cmd, TIMEOUT, "PDF/A conversion");
            if (!Files.isRegularFile(target) || Files.size(target) == 0) {
                throw new ConversionException("PDF/A conversion produced no output");
            }
        } catch (IOException e) {
            throw new ConversionException("PDF/A conversion failed: " + e.getMessage(), e);
        } finally {
            deleteQuietly(work);
        }
    }

    /** The standard Ghostscript PDFA_def.ps: declares the sRGB output intent PDF/A requires. */
    private static String pdfaDefinition(Path icc) {
        String path = icc.toAbsolutePath().toString().replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)");
        return "%!\n"
                + "/ICCProfile (" + path + ") def\n"
                + "[/_objdef {icc_PDFA} /type /stream /OBJ pdfmark\n"
                + "[{icc_PDFA} << /N 3 >> /PUT pdfmark\n"
                + "[{icc_PDFA} ICCProfile (r) file /PUT pdfmark\n"
                + "[/_objdef {OutputIntent_PDFA} /type /dict /OBJ pdfmark\n"
                + "[{OutputIntent_PDFA} << /Type /OutputIntent /S /GTS_PDFA1 /DestOutputProfile {icc_PDFA}"
                + " /OutputConditionIdentifier (sRGB) >> /PUT pdfmark\n"
                + "[{Catalog} << /OutputIntents [ {OutputIntent_PDFA} ] >> /PUT pdfmark\n";
    }

    /** Ghostscript ships an sRGB profile; find it (or use DOCSMELT_ICC). */
    static Path iccProfile() {
        String env = System.getenv("DOCSMELT_ICC");
        if (env != null && Files.isRegularFile(Path.of(env))) {
            return Path.of(env);
        }
        Path root = Path.of("/usr/share/ghostscript");
        if (!Files.isDirectory(root)) {
            return null;
        }
        try (DirectoryStream<Path> versions = Files.newDirectoryStream(root)) {
            for (Path v : versions) {
                Path icc = v.resolve("iccprofiles/srgb.icc");
                if (Files.isRegularFile(icc)) {
                    return icc;
                }
            }
        } catch (IOException ignored) {
            // fall through
        }
        return null;
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

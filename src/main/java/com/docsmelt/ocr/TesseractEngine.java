package com.docsmelt.ocr;

import com.docsmelt.io.ConversionException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs the {@code tesseract} command-line tool. The binary can be overridden
 * with the {@code DOCSMELT_TESSERACT} environment variable.
 */
public final class TesseractEngine implements OcrEngine {

    private static final Duration TIMEOUT = Duration.ofMinutes(10);

    private final String binary;
    private volatile List<String> languages; // cached after first lookup

    public TesseractEngine() {
        this(System.getenv().getOrDefault("DOCSMELT_TESSERACT", "tesseract"));
    }

    public TesseractEngine(String binary) {
        this.binary = binary;
    }

    @Override
    public boolean isAvailable() {
        return !languages().isEmpty();
    }

    @Override
    public List<String> languages() {
        List<String> cached = languages;
        if (cached == null) {
            cached = lookupLanguages();
            languages = cached;
        }
        return cached;
    }

    private List<String> lookupLanguages() {
        try {
            String out = run(List.of(binary, "--list-langs"), Duration.ofSeconds(20));
            List<String> langs = new ArrayList<>();
            for (String line : out.split("\\R")) {
                String l = line.strip();
                // First line is a header ("List of available languages ..."); "osd" isn't a language.
                if (l.matches("[A-Za-z_]+") && !l.equals("osd")) {
                    langs.add(l);
                }
            }
            return List.copyOf(langs);
        } catch (ConversionException e) {
            return List.of();
        }
    }

    @Override
    public void recognize(List<Path> images, String languages, int dpi, Path outputBase, Set<Output> outputs) {
        try {
            // Tesseract accepts a text file listing images and produces one multi-page result.
            Path list = Files.createTempFile(outputBase.getParent(), "pages", ".txt");
            Files.write(list, images.stream().map(p -> p.toAbsolutePath().toString()).toList());

            List<String> cmd = new ArrayList<>(List.of(binary, list.toString(), outputBase.toString(),
                    "-l", languages, "--dpi", String.valueOf(dpi)));
            if (outputs.contains(Output.SEARCHABLE_PDF)) {
                cmd.add("pdf");
            }
            if (outputs.contains(Output.TEXT)) {
                cmd.add("txt");
            }
            run(cmd, TIMEOUT);
        } catch (IOException e) {
            throw new ConversionException("OCR failed: " + e.getMessage(), e);
        }
    }

    private static String run(List<String> cmd, Duration timeout) {
        Process process;
        try {
            process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        } catch (IOException e) {
            throw new ConversionException("OCR is not available: '" + cmd.getFirst() + "' could not be started. "
                    + "Install Tesseract (e.g. apt install tesseract-ocr).", e);
        }
        // A watchdog kills the process if it runs too long; reading its output then ends too.
        AtomicBoolean timedOut = new AtomicBoolean();
        Thread watchdog = Thread.ofVirtual().start(() -> {
            try {
                if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    timedOut.set(true);
                    process.destroyForcibly();
                }
            } catch (InterruptedException finishedFirst) {
                Thread.currentThread().interrupt(); // run() finished first; just stop watching
            }
        });
        try (InputStream out = process.getInputStream()) {
            String text = new String(out.readAllBytes(), StandardCharsets.UTF_8); // drains until exit
            int exit = process.waitFor();
            if (timedOut.get()) {
                throw new ConversionException("OCR timed out after " + timeout.toSeconds() + " seconds");
            }
            if (exit != 0) {
                throw new ConversionException("OCR failed: " + tail(text));
            }
            return text;
        } catch (IOException e) {
            throw new ConversionException("OCR failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ConversionException("OCR was interrupted");
        } finally {
            watchdog.interrupt();
            process.destroyForcibly();
        }
    }

    private static String tail(String text) {
        String t = text.strip();
        return t.length() > 400 ? "…" + t.substring(t.length() - 400) : t;
    }
}

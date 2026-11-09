package com.filekit.core;

import com.filekit.io.PageRange;
import com.filekit.io.ReadOptions;
import com.filekit.io.WriteOptions;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * User-facing conversion settings. The {@link Converter} splits them into the
 * {@link ReadOptions} and {@link WriteOptions} each side needs.
 *
 * @param dpi         resolution for page rendering, 36–1200
 * @param pages       page selection
 * @param jpegQuality 0.0–1.0
 * @param extraFonts  fallback TrueType fonts for PDF output
 */
public record ConversionOptions(int dpi, PageRange pages, float jpegQuality, List<Path> extraFonts) {

    public static final ConversionOptions DEFAULT =
            new ConversionOptions(150, PageRange.ALL, 0.9f, List.of());

    public ConversionOptions {
        if (dpi < 36 || dpi > 1200) {
            throw new IllegalArgumentException("dpi must be between 36 and 1200, got " + dpi);
        }
        if (jpegQuality < 0f || jpegQuality > 1f) {
            throw new IllegalArgumentException("JPEG quality must be between 0 and 1, got " + jpegQuality);
        }
        Objects.requireNonNull(pages, "pages");
        extraFonts = List.copyOf(extraFonts);
    }

    ReadOptions toReadOptions(boolean renderSnapshots) {
        return new ReadOptions(renderSnapshots, dpi, pages);
    }

    WriteOptions toWriteOptions() {
        return new WriteOptions(dpi, jpegQuality, extraFonts);
    }
}

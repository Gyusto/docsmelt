package com.docsmelt.io;

import java.nio.file.Path;
import java.util.List;

/**
 * Settings passed to a {@link DocumentWriter}.
 *
 * @param dpi          resolution for writers that rasterize content
 * @param jpegQuality  0.0–1.0, used by lossy image writers
 * @param extraFonts   TrueType fonts tried (in order) for characters the
 *                     bundled font lacks, e.g. CJK
 */
public record WriteOptions(int dpi, float jpegQuality, List<Path> extraFonts) {

    public static final WriteOptions DEFAULT = new WriteOptions(150, 0.9f, List.of());

    public WriteOptions {
        extraFonts = List.copyOf(extraFonts);
    }
}

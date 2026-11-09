package com.docsmelt.ocr;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/** Recognizes text in page images. */
public interface OcrEngine {

    enum Output { SEARCHABLE_PDF, TEXT }

    boolean isAvailable();

    /** Installed language codes, e.g. ["eng", "swa"]. Empty when unavailable. */
    List<String> languages();

    /**
     * Runs recognition over the images (one page each, in order).
     *
     * @param languages  e.g. "eng" or "eng+swa"
     * @param dpi        resolution of the images, used for the PDF page size
     * @param outputBase path without extension; the engine writes
     *                   {@code outputBase.pdf} and/or {@code outputBase.txt}
     *                   (pages separated by form feeds)
     */
    void recognize(List<Path> images, String languages, int dpi, Path outputBase, Set<Output> outputs);
}

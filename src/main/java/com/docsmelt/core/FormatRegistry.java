package com.docsmelt.core;

import com.docsmelt.format.Format;
import com.docsmelt.format.UnsupportedFormatException;
import com.docsmelt.io.DocumentReader;
import com.docsmelt.io.DocumentWriter;
import com.docsmelt.io.docx.DocxDocumentReader;
import com.docsmelt.io.docx.DocxDocumentWriter;
import com.docsmelt.io.image.ImageDocumentReader;
import com.docsmelt.io.image.ImageDocumentWriter;
import com.docsmelt.io.pdf.PdfDocumentReader;
import com.docsmelt.io.pdf.PdfDocumentWriter;
import com.docsmelt.io.text.TextDocumentReader;
import com.docsmelt.io.text.TextDocumentWriter;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Factory that maps formats to reader/writer strategies. New formats are added
 * by registering them — no existing class has to change (open/closed principle).
 */
public final class FormatRegistry {

    private final Map<Format, Supplier<DocumentReader>> readers = new EnumMap<>(Format.class);
    private final Map<Format, Supplier<DocumentWriter>> writers = new EnumMap<>(Format.class);

    /** A registry with every built-in format wired up. */
    public static FormatRegistry withDefaults() {
        FormatRegistry registry = new FormatRegistry()
                .registerReader(Format.PDF, PdfDocumentReader::new)
                .registerWriter(Format.PDF, PdfDocumentWriter::new)
                .registerReader(Format.DOCX, DocxDocumentReader::new)
                .registerWriter(Format.DOCX, DocxDocumentWriter::new)
                .registerReader(Format.TXT, TextDocumentReader::new)
                .registerWriter(Format.TXT, TextDocumentWriter::new);
        for (Format f : Format.values()) {
            if (f.isImage()) {
                registry.registerReader(f, ImageDocumentReader::new);
                registry.registerWriter(f, () -> new ImageDocumentWriter(f));
            }
        }
        return registry;
    }

    public FormatRegistry registerReader(Format format, Supplier<DocumentReader> factory) {
        readers.put(format, factory);
        return this;
    }

    public FormatRegistry registerWriter(Format format, Supplier<DocumentWriter> factory) {
        writers.put(format, factory);
        return this;
    }

    public DocumentReader readerFor(Format format) {
        Supplier<DocumentReader> factory = readers.get(format);
        if (factory == null) {
            throw new UnsupportedFormatException("Reading " + format + " is not supported");
        }
        return factory.get();
    }

    public DocumentWriter writerFor(Format format) {
        Supplier<DocumentWriter> factory = writers.get(format);
        if (factory == null) {
            throw new UnsupportedFormatException("Writing " + format + " is not supported");
        }
        return factory.get();
    }

    public Set<Format> readableFormats() {
        return Collections.unmodifiableSet(readers.keySet());
    }

    public Set<Format> writableFormats() {
        return Collections.unmodifiableSet(writers.keySet());
    }
}

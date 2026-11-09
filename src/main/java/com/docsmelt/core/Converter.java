package com.docsmelt.core;

import com.docsmelt.format.Format;
import com.docsmelt.io.ConversionException;
import com.docsmelt.io.DocumentReader;
import com.docsmelt.io.DocumentWriter;
import com.docsmelt.model.Document;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Orchestrates a single conversion: detect formats → read into the model → write out.
 * Stateless and thread-safe; each call gets fresh reader/writer instances.
 */
public final class Converter {

    private final FormatRegistry registry;

    public Converter() {
        this(FormatRegistry.withDefaults());
    }

    public Converter(FormatRegistry registry) {
        this.registry = registry;
    }

    public List<Path> convert(Path source, Path target) {
        return convert(source, target, ConversionOptions.DEFAULT);
    }

    /** @return the file(s) produced */
    public List<Path> convert(Path source, Path target, ConversionOptions options) {
        if (!Files.isRegularFile(source)) {
            throw new ConversionException("Input file not found: " + source);
        }
        if (source.toAbsolutePath().normalize().equals(target.toAbsolutePath().normalize())) {
            throw new ConversionException("Output would overwrite the input: " + source);
        }
        DocumentReader reader = registry.readerFor(Format.fromPath(source));
        DocumentWriter writer = registry.writerFor(Format.fromPath(target));

        createParentDirectories(target);
        Document document = reader.read(source, options.toReadOptions(writer.wantsSnapshots()));
        return writer.write(document, target, options.toWriteOptions());
    }

    public FormatRegistry registry() {
        return registry;
    }

    private static void createParentDirectories(Path target) {
        Path parent = target.toAbsolutePath().getParent();
        try {
            Files.createDirectories(parent);
        } catch (IOException e) {
            throw new ConversionException("Cannot create output directory " + parent + ": " + e.getMessage(), e);
        }
    }
}

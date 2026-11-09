package com.filekit.core.batch;

import java.nio.file.Path;
import java.util.List;

/**
 * Observer for batch progress (CLI output, GUI progress bars, ...).
 * Implementations must be thread-safe when the batch runs in parallel.
 */
public interface ConversionListener {

    ConversionListener NONE = new ConversionListener() { };

    default void started(ConversionJob job) {
    }

    default void succeeded(ConversionJob job, List<Path> outputs) {
    }

    default void failed(ConversionJob job, Exception error) {
    }
}

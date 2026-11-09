package com.docsmelt.core.batch;

import java.nio.file.Path;

/** One planned conversion. */
public record ConversionJob(Path source, Path target) {
}

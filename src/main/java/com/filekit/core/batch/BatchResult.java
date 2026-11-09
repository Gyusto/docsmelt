package com.filekit.core.batch;

import java.nio.file.Path;
import java.util.List;

/** Outcome of a batch run. */
public record BatchResult(List<Success> successes, List<Failure> failures) {

    public record Success(ConversionJob job, List<Path> outputs) {
    }

    public record Failure(ConversionJob job, Exception error) {
    }

    public BatchResult {
        successes = List.copyOf(successes);
        failures = List.copyOf(failures);
    }

    public boolean allSucceeded() {
        return failures.isEmpty();
    }

    public int total() {
        return successes.size() + failures.size();
    }
}

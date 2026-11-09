package com.filekit.core.batch;

import com.filekit.core.ConversionOptions;
import com.filekit.core.Converter;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Runs many {@link ConversionJob}s, optionally in parallel. A failing job never
 * stops the others; failures are collected into the {@link BatchResult}.
 */
public final class BatchConverter {

    private final Converter converter;
    private final int parallelism;

    public BatchConverter(Converter converter, int parallelism) {
        if (parallelism < 1) {
            throw new IllegalArgumentException("parallelism must be >= 1");
        }
        this.converter = converter;
        this.parallelism = parallelism;
    }

    public BatchResult run(List<ConversionJob> jobs, ConversionOptions options, ConversionListener listener) {
        List<BatchResult.Success> successes = Collections.synchronizedList(new ArrayList<>());
        List<BatchResult.Failure> failures = Collections.synchronizedList(new ArrayList<>());

        try (ExecutorService pool = Executors.newFixedThreadPool(Math.min(parallelism, Math.max(1, jobs.size())))) {
            List<Future<?>> futures = new ArrayList<>();
            for (ConversionJob job : jobs) {
                futures.add(pool.submit(() -> {
                    listener.started(job);
                    try {
                        List<Path> outputs = converter.convert(job.source(), job.target(), options);
                        successes.add(new BatchResult.Success(job, outputs));
                        listener.succeeded(job, outputs);
                    } catch (Exception e) {
                        failures.add(new BatchResult.Failure(job, e));
                        listener.failed(job, e);
                    }
                }));
            }
            for (Future<?> f : futures) {
                f.get();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            throw new IllegalStateException("Unexpected batch error", e.getCause());
        }
        return new BatchResult(successes, failures);
    }
}

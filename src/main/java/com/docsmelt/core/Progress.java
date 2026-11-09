package com.docsmelt.core;

import com.docsmelt.io.ConversionException;

/**
 * Lets long-running work report "step N of M" without threading a listener
 * through every method: a background job installs a listener for its thread,
 * and loops call {@link #step}. Without a listener (CLI, tests) it costs nothing.
 *
 * <p>{@link #step} is also a cancellation point: if the job's thread was
 * interrupted (timeout or user cancel), the work stops there.
 */
public final class Progress {

    @FunctionalInterface
    public interface Listener {
        void update(int done, int total);
    }

    private static final ThreadLocal<Listener> CURRENT = new ThreadLocal<>();

    private Progress() {
    }

    public static void install(Listener listener) {
        CURRENT.set(listener);
    }

    public static void clear() {
        CURRENT.remove();
    }

    /** Reports progress and stops the work if it has been cancelled. */
    public static void step(int done, int total) {
        if (Thread.currentThread().isInterrupted()) {
            throw new ConversionException("Cancelled");
        }
        Listener listener = CURRENT.get();
        if (listener != null) {
            listener.update(done, total);
        }
    }
}

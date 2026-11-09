package com.docsmelt.io;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs an external tool (Tesseract, Ghostscript) with a time limit. A watchdog
 * kills the process if it overruns; an interrupted caller (cancelled job)
 * kills it too.
 */
public final class ProcessRunner {

    private ProcessRunner() {
    }

    /** @return the tool's combined output; throws on a non-zero exit, timeout or cancel */
    public static String run(List<String> cmd, Duration timeout, String toolName) {
        Process process;
        try {
            process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        } catch (IOException e) {
            throw new ConversionException(toolName + " is not available: '" + cmd.getFirst() + "' could not be started", e);
        }
        AtomicBoolean timedOut = new AtomicBoolean();
        Thread caller = Thread.currentThread();
        Thread watchdog = Thread.ofVirtual().start(() -> {
            try {
                if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    timedOut.set(true);
                    process.destroyForcibly();
                }
            } catch (InterruptedException finished) {
                Thread.currentThread().interrupt(); // run() finished first
            }
        });
        try (InputStream out = process.getInputStream()) {
            String text = readUntilExit(process, out, caller);
            int exit = process.waitFor();
            if (timedOut.get()) {
                throw new ConversionException(toolName + " timed out after " + timeout.toSeconds() + " seconds");
            }
            if (exit != 0) {
                throw new ConversionException(toolName + " failed: " + tail(text));
            }
            return text;
        } catch (IOException e) {
            throw new ConversionException(toolName + " failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ConversionException("Cancelled");
        } finally {
            watchdog.interrupt();
            process.destroyForcibly();
        }
    }

    /** Drains output; if the calling job is cancelled meanwhile, kills the process. */
    private static String readUntilExit(Process process, InputStream out, Thread caller) throws IOException, InterruptedException {
        Thread killer = Thread.ofVirtual().start(() -> {
            while (process.isAlive()) {
                if (caller.isInterrupted()) {
                    process.destroyForcibly();
                    return;
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
        try {
            String text = new String(out.readAllBytes(), StandardCharsets.UTF_8);
            if (caller.isInterrupted()) {
                throw new InterruptedException();
            }
            return text;
        } finally {
            killer.interrupt();
        }
    }

    private static String tail(String text) {
        String t = text.strip();
        return t.length() > 400 ? "…" + t.substring(t.length() - 400) : t;
    }
}

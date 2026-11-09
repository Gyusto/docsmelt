package com.docsmelt.web;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One piece of work running in the background. Its id is a random UUID, which
 * also acts as the key to its result: only the browser that started it knows it.
 */
final class Job {

    enum State { QUEUED, RUNNING, DONE, FAILED }

    /** What a finished job produced: a file in its workspace, or a JSON body. */
    record Result(Path file, String filename, String contentType, String json) {
        static Result json(String body) {
            return new Result(null, null, "application/json", body);
        }
    }

    final String id = UUID.randomUUID().toString();
    final Workspace workspace;
    final long createdAt = System.currentTimeMillis();

    volatile State state = State.QUEUED;
    volatile long startedAt;
    volatile long finishedAt;
    volatile int done;
    volatile int total;
    volatile Result result;
    volatile int errorStatus;
    volatile String error;
    volatile Throwable cause;
    volatile Future<?> future;

    private final AtomicBoolean disposed = new AtomicBoolean();

    Job(Workspace workspace) {
        this.workspace = workspace;
    }

    void fail(int status, String message, Throwable cause) {
        this.errorStatus = status;
        this.error = message;
        this.cause = cause;
        this.state = State.FAILED;
        this.finishedAt = System.currentTimeMillis();
    }

    boolean finished() {
        return state == State.DONE || state == State.FAILED;
    }

    /** Deletes the job's files. Safe to call more than once. */
    void dispose() {
        if (disposed.compareAndSet(false, true)) {
            workspace.close();
        }
    }

    String statusJson() {
        return switch (state) {
            case QUEUED, RUNNING -> "{\"state\":\"" + state.name().toLowerCase() + "\",\"done\":" + done + ",\"total\":" + total + "}";
            case DONE -> "{\"state\":\"done\"" + (result.filename() == null ? ""
                    : ",\"filename\":\"" + WebServer.escape(result.filename()) + "\"") + "}";
            case FAILED -> "{\"state\":\"failed\",\"status\":" + errorStatus + ",\"error\":\"" + WebServer.escape(error) + "\"}";
        };
    }
}

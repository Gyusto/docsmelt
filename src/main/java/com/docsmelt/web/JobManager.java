package com.docsmelt.web;

import com.docsmelt.core.Progress;
import com.docsmelt.io.ConversionException;
import io.javalin.http.HttpResponseException;
import io.javalin.http.ServiceUnavailableResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs tool work on a bounded pool:
 * <ul>
 *   <li>at most {@code maxConcurrent} jobs run at once; up to {@code maxQueue} wait;
 *       beyond that new work is refused with 503;</li>
 *   <li>every job has a time limit; overdue jobs are interrupted and reported as failed;</li>
 *   <li>results nobody collects are deleted after {@link #KEEP_RESULTS_MS}.</li>
 * </ul>
 */
final class JobManager implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(JobManager.class);
    static final long KEEP_RESULTS_MS = TimeUnit.MINUTES.toMillis(10);

    private final ThreadPoolExecutor pool;
    private final ScheduledExecutorService janitor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "docsmelt-janitor");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private final long timeoutMs;

    JobManager(ServerConfig config) {
        this.timeoutMs = TimeUnit.SECONDS.toMillis(config.jobTimeoutSeconds());
        this.pool = new ThreadPoolExecutor(config.maxConcurrent(), config.maxConcurrent(), 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(config.maxQueue()), r -> {
                    Thread t = new Thread(r, "docsmelt-worker");
                    t.setDaemon(true);
                    return t;
                });
        janitor.scheduleAtFixedRate(this::sweep, 5, 5, TimeUnit.SECONDS);
    }

    Job submit(Workspace workspace, Callable<Job.Result> work) {
        Job job = new Job(workspace);
        jobs.put(job.id, job);
        try {
            job.future = pool.submit(() -> run(job, work));
        } catch (RejectedExecutionException e) {
            jobs.remove(job.id);
            job.dispose();
            throw new ServiceUnavailableResponse("Server is busy, please try again in a moment");
        }
        return job;
    }

    private void run(Job job, Callable<Job.Result> work) {
        if (job.finished()) {
            return; // cancelled while waiting in the queue
        }
        job.startedAt = System.currentTimeMillis();
        job.state = Job.State.RUNNING;
        Progress.install((done, total) -> {
            job.done = done;
            job.total = total;
        });
        try {
            Job.Result result = work.call();
            if (job.state == Job.State.RUNNING) {
                job.result = result;
                job.finishedAt = System.currentTimeMillis();
                job.state = Job.State.DONE;
            }
        } catch (Throwable e) {
            if (!job.finished()) {
                job.fail(status(e), message(e), e);
                if (status(e) == 500) {
                    LOG.error("Job failed", e);
                }
            }
        } finally {
            Progress.clear();
            Thread.interrupted(); // don't leak an interrupt into the next job on this thread
            if (job.state != Job.State.DONE) {
                job.dispose();
            }
        }
    }

    Job get(String id) {
        return jobs.get(id);
    }

    void cancel(Job job, int status, String message) {
        if (!job.finished()) {
            job.fail(status, message, null);
        }
        if (job.future != null) {
            job.future.cancel(true);
            pool.purge(); // a cancelled job still waiting in the queue gives its place back now
        }
    }

    /** Called once the result has been delivered (or will never be). */
    void forget(Job job) {
        jobs.remove(job.id);
        job.dispose();
    }

    /** Waits for a job started by a synchronous request. */
    Job.Result await(Job job) throws Exception {
        try {
            job.future.get(timeoutMs + TimeUnit.SECONDS.toMillis(60), TimeUnit.MILLISECONDS); // + time in the queue
        } catch (TimeoutException e) {
            cancel(job, 503, tooLong());
        } catch (ExecutionException | java.util.concurrent.CancellationException e) {
            // the job recorded its own failure
        }
        if (job.state == Job.State.DONE) {
            return job.result;
        }
        if (job.cause instanceof Exception e) {
            throw e; // the normal error mapping turns it into a JSON error response
        }
        throw new HttpResponseException(job.errorStatus == 0 ? 500 : job.errorStatus,
                job.error == null ? "Something went wrong on the server" : job.error);
    }

    private void sweep() {
        long now = System.currentTimeMillis();
        for (Job job : jobs.values()) {
            if (job.state == Job.State.RUNNING && now - job.startedAt > timeoutMs) {
                cancel(job, 503, tooLong());
            } else if (job.state == Job.State.QUEUED && now - job.createdAt > timeoutMs * 2) {
                cancel(job, 503, "Server is busy, please try again in a moment");
            } else if (job.finished() && now - job.finishedAt > KEEP_RESULTS_MS) {
                forget(job); // nobody collected the result: delete it
            }
        }
    }

    private String tooLong() {
        return "This took too long (limit " + TimeUnit.MILLISECONDS.toMinutes(timeoutMs) + " minutes); try a smaller file or fewer pages";
    }

    private static int status(Throwable e) {
        if (e instanceof HttpResponseException h) {
            return h.getStatus();
        }
        if (e instanceof ConversionException ce && "Cancelled".equals(ce.getMessage())) {
            return 499;
        }
        return e instanceof ConversionException || e instanceof IllegalArgumentException ? 400 : 500;
    }

    private static String message(Throwable e) {
        return status(e) == 500 ? "Something went wrong on the server" : e.getMessage();
    }

    int running() {
        return pool.getActiveCount();
    }

    @Override
    public void close() {
        janitor.shutdownNow();
        pool.shutdownNow();
        jobs.values().forEach(Job::dispose);
        jobs.clear();
    }
}

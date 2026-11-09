package com.docsmelt.web;

/**
 * Web server settings.
 *
 * @param host              interface to bind ("127.0.0.1" = this machine only, "0.0.0.0" = everyone)
 * @param port              TCP port, 0 = pick a free one
 * @param maxUploadMb       limit for one uploaded file
 * @param maxFiles          files per request
 * @param requestsPerMinute per client IP, for the conversion endpoints
 * @param maxConcurrent     conversions running at once; extra requests wait in a queue
 * @param maxPages          largest PDF (in pages) accepted
 * @param jobTimeoutSeconds time limit for one job
 * @param maxQueue          jobs allowed to wait; beyond that requests get 503
 */
public record ServerConfig(String host, int port, int maxUploadMb, int maxFiles,
                           int requestsPerMinute, int maxConcurrent,
                           int maxPages, int jobTimeoutSeconds, int maxQueue) {

    public static ServerConfig defaults() {
        return new ServerConfig("127.0.0.1", 8080, 50, 20, 30,
                Math.max(2, Runtime.getRuntime().availableProcessors()));
    }

    /** With default page limit (1000), job time limit (5 min) and queue length (100). */
    public ServerConfig(String host, int port, int maxUploadMb, int maxFiles, int requestsPerMinute, int maxConcurrent) {
        this(host, port, maxUploadMb, maxFiles, requestsPerMinute, maxConcurrent, 1000, 300, 100);
    }

    public ServerConfig {
        if (maxUploadMb < 1 || maxFiles < 1 || requestsPerMinute < 1 || maxConcurrent < 1
                || maxPages < 1 || jobTimeoutSeconds < 1 || maxQueue < 1) {
            throw new IllegalArgumentException("Server limits must be positive");
        }
    }

    public ServerConfig withPort(int newPort) {
        return new ServerConfig(host, newPort, maxUploadMb, maxFiles, requestsPerMinute, maxConcurrent,
                maxPages, jobTimeoutSeconds, maxQueue);
    }

    long maxUploadBytes() {
        return maxUploadMb * 1024L * 1024L;
    }
}

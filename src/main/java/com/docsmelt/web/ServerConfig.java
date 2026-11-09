package com.docsmelt.web;

/**
 * Web server settings.
 *
 * @param host              interface to bind ("127.0.0.1" = this machine only, "0.0.0.0" = everyone)
 * @param port              TCP port, 0 = pick a free one
 * @param maxUploadMb       limit for one uploaded file
 * @param maxFiles          files per request
 * @param requestsPerMinute per client IP, for the conversion endpoints
 * @param maxConcurrent     conversions running at once; extra requests wait briefly, then get 503
 */
public record ServerConfig(String host, int port, int maxUploadMb, int maxFiles,
                           int requestsPerMinute, int maxConcurrent) {

    public static ServerConfig defaults() {
        return new ServerConfig("127.0.0.1", 8080, 50, 20, 30,
                Math.max(2, Runtime.getRuntime().availableProcessors()));
    }

    public ServerConfig {
        if (maxUploadMb < 1 || maxFiles < 1 || requestsPerMinute < 1 || maxConcurrent < 1) {
            throw new IllegalArgumentException("Server limits must be positive");
        }
    }

    public ServerConfig withPort(int newPort) {
        return new ServerConfig(host, newPort, maxUploadMb, maxFiles, requestsPerMinute, maxConcurrent);
    }

    long maxUploadBytes() {
        return maxUploadMb * 1024L * 1024L;
    }
}

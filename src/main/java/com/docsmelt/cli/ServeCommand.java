package com.docsmelt.cli;

import com.docsmelt.core.Converter;
import com.docsmelt.web.ServerConfig;
import com.docsmelt.web.WebServer;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;

@Command(name = "serve", mixinStandardHelpOptions = true, sortOptions = false,
        description = "Start the web UI.",
        footer = {"", "Examples:",
                "  docsmelt serve                     http://127.0.0.1:8080 (this computer only)",
                "  docsmelt serve --host 0.0.0.0      reachable from your network"})
final class ServeCommand implements Callable<Integer> {

    private static final ServerConfig DEFAULTS = ServerConfig.defaults();

    @Option(names = "--host", defaultValue = "127.0.0.1", description = "Interface to bind (default: ${DEFAULT-VALUE}).")
    String host;

    @Option(names = {"-p", "--port"}, defaultValue = "8080", description = "Port (default: ${DEFAULT-VALUE}).")
    int port;

    @Option(names = "--max-upload-mb", defaultValue = "50", description = "Max size per file (default: ${DEFAULT-VALUE}).")
    int maxUploadMb;

    @Option(names = "--max-files", defaultValue = "20", description = "Max files per request (default: ${DEFAULT-VALUE}).")
    int maxFiles;

    @Option(names = "--max-pages", defaultValue = "1000", description = "Largest PDF accepted, in pages (default: ${DEFAULT-VALUE}).")
    int maxPages;

    @Option(names = "--job-timeout", defaultValue = "300", description = "Time limit per job in seconds (default: ${DEFAULT-VALUE}).")
    int jobTimeout;

    @Option(names = "--rate-limit", defaultValue = "30",
            description = "Requests per minute per IP (default: ${DEFAULT-VALUE}).")
    int rateLimit;

    @Override
    public Integer call() throws InterruptedException {
        ServerConfig config = new ServerConfig(host, port, maxUploadMb, maxFiles, rateLimit, DEFAULTS.maxConcurrent(),
                maxPages, jobTimeout, 100);
        WebServer server = new WebServer(config, new Converter()).start();
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        new CountDownLatch(1).await(); // run until Ctrl+C / container stop
        return 0;
    }
}

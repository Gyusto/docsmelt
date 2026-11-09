package com.docsmelt.web;

import com.docsmelt.core.Converter;
import com.docsmelt.format.Format;
import com.docsmelt.io.ConversionException;
import com.docsmelt.ocr.OcrService;
import com.docsmelt.ocr.TesseractEngine;
import com.docsmelt.pdf.PdfMerger;
import com.docsmelt.pdf.PdfSplitter;
import io.javalin.Javalin;
import io.javalin.config.SizeUnit;
import io.javalin.http.Context;
import io.javalin.http.HttpResponseException;
import io.javalin.http.staticfiles.Location;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;

/**
 * Serves the single-page UI from {@code /public} and the JSON/file API:
 *
 * <pre>
 *   GET  /api/formats   supported formats
 *   GET  /api/health    "ok"
 *   POST /api/convert   multipart: files[], to, pages?, dpi?, quality?
 *   POST /api/merge     multipart: files[] (2+)
 *   POST /api/split     multipart: files (1 PDF), every? | ranges?
 *   POST /api/preview   multipart: files (1 PDF) → JSON thumbnails
 *   POST /api/organize  multipart: files (1 PDF), order, rotate?
 *   POST /api/compress  multipart: files[] (PDFs), level?
 *   POST /api/images-to-pdf  multipart: files[] (images), pageSize?, margin?
 *   POST /api/ocr       multipart: files (1 PDF/image), lang?, output?, pages?
 *   POST /api/protect   multipart: files (1 PDF), password, allowPrint?, allowCopy?, allowEdit?
 *   POST /api/unlock    multipart: files (1 PDF), password
 *   POST /api/number    multipart: files (1 PDF), position?, format?, start?, pages?, size?
 *   POST /api/sign      multipart: files (PDF + signature image), page?, x, y, width
 * </pre>
 */
public final class WebServer implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(WebServer.class);

    private final ServerConfig config;
    private final Javalin app;

    public WebServer(ServerConfig config, Converter converter) {
        this(config, converter, new OcrService(new TesseractEngine()));
    }

    public WebServer(ServerConfig config, Converter converter, OcrService ocr) {
        this.config = config;
        Semaphore slots = new Semaphore(config.maxConcurrent());

        this.app = Javalin.create(c -> {
            c.showJavalinBanner = false;
            c.staticFiles.add("/public", Location.CLASSPATH);
            c.staticFiles.enableWebjars(); // Font Awesome
            c.jetty.multipartConfig.maxFileSize(config.maxUploadMb() + 1L, SizeUnit.MB);
            c.jetty.multipartConfig.maxTotalRequestSize((long) (config.maxUploadMb() + 1) * config.maxFiles(), SizeUnit.MB);
            c.jetty.multipartConfig.maxInMemoryFileSize(1, SizeUnit.MB);
        });

        app.before(WebServer::securityHeaders);
        app.get("/api/health", ctx -> ctx.result("ok"));
        app.get("/api/formats", ctx -> json(ctx, 200, formatsJson(converter, config, ocr)));
        app.post("/api/convert", new ConvertHandler(config, slots, converter));
        app.post("/api/merge", new MergeHandler(config, slots, new PdfMerger(converter)));
        app.post("/api/split", new SplitHandler(config, slots, new PdfSplitter()));
        app.post("/api/preview", new PreviewHandler(config, slots));
        app.post("/api/organize", new OrganizeHandler(config, slots));
        app.post("/api/compress", new CompressHandler(config, slots));
        app.post("/api/images-to-pdf", new ImagesHandler(config, slots));
        app.post("/api/ocr", new OcrHandler(config, slots, ocr));
        app.post("/api/protect", new SecurityHandler(config, slots, SecurityHandler.Mode.PROTECT));
        app.post("/api/unlock", new SecurityHandler(config, slots, SecurityHandler.Mode.UNLOCK));
        app.post("/api/number", new NumberHandler(config, slots));
        app.post("/api/sign", new SignHandler(config, slots));

        app.exception(ConversionException.class, (e, ctx) -> error(ctx, 400, e.getMessage()));
        app.exception(IllegalArgumentException.class, (e, ctx) -> error(ctx, 400, e.getMessage()));
        app.exception(HttpResponseException.class, (e, ctx) -> error(ctx, e.getStatus(), e.getMessage()));
        app.exception(Exception.class, (e, ctx) -> {
            LOG.error("Unexpected error on {}", ctx.path(), e);
            error(ctx, 500, "Something went wrong on the server");
        });
    }

    public WebServer start() {
        app.start(config.host(), config.port());
        LOG.info("docsmelt web UI running at http://{}:{}/", config.host().equals("0.0.0.0") ? "localhost" : config.host(), port());
        return this;
    }

    public int port() {
        return app.port();
    }

    @Override
    public void close() {
        app.stop();
    }

    private static void securityHeaders(Context ctx) {
        ctx.header("X-Content-Type-Options", "nosniff");
        ctx.header("Referrer-Policy", "no-referrer");
        ctx.header("Content-Security-Policy",
                "default-src 'self'; img-src 'self' data: blob:; style-src 'self'; frame-ancestors 'none'");
    }

    private static String formatsJson(Converter converter, ServerConfig config, OcrService ocr) {
        String langs = ocr.engine().languages().stream()
                .map(l -> "\"" + escape(l) + "\"")
                .collect(Collectors.joining(",", "[", "]"));
        return "{\"read\":" + extensions(converter.registry().readableFormats())
                + ",\"write\":" + extensions(converter.registry().writableFormats())
                + ",\"maxUploadMb\":" + config.maxUploadMb()
                + ",\"maxFiles\":" + config.maxFiles()
                + ",\"ocr\":{\"available\":" + ocr.engine().isAvailable() + ",\"languages\":" + langs
                + ",\"maxPages\":" + OcrHandler.MAX_PAGES + "}}";
    }

    private static String extensions(Collection<Format> formats) {
        return formats.stream()
                .flatMap(f -> f.extensions().stream())
                .map(e -> "\"" + e + "\"")
                .collect(Collectors.joining(",", "[", "]"));
    }

    private static void error(Context ctx, int status, String message) {
        json(ctx, status, "{\"error\":\"" + escape(message == null ? "Error" : message) + "\"}");
    }

    private static void json(Context ctx, int status, String body) {
        ctx.status(status).contentType("application/json").result(body);
    }

    static String escape(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}

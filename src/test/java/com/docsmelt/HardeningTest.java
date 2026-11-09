package com.docsmelt;

import com.docsmelt.core.Converter;
import com.docsmelt.io.ConversionException;
import com.docsmelt.io.Limits;
import com.docsmelt.io.docx.DocxDocumentWriter;
import com.docsmelt.io.image.ImageLoader;
import com.docsmelt.model.Document;
import com.docsmelt.model.Paragraph;
import com.docsmelt.ocr.OcrService;
import com.docsmelt.web.ServerConfig;
import com.docsmelt.web.WebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static com.docsmelt.TestFiles.*;
import static org.junit.jupiter.api.Assertions.*;

class HardeningTest {

    @TempDir
    Path dir;

    private WebServer server;
    private final HttpClient http = HttpClient.newHttpClient();

    @AfterEach
    void stop() {
        if (server != null) {
            server.close();
        }
    }

    // ---- hostile files -------------------------------------------------------

    /** A valid PNG header claiming {@code w × h} pixels: a few bytes that would decode to gigabytes. */
    private Path pngBomb(int w, int h) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
        ByteArrayOutputStream ihdr = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(ihdr);
        d.writeBytes("IHDR");
        d.writeInt(w);
        d.writeInt(h);
        d.write(new byte[]{8, 2, 0, 0, 0}); // 8-bit RGB
        writeChunk(out, ihdr.toByteArray());
        ByteArrayOutputStream iend = new ByteArrayOutputStream();
        iend.write("IEND".getBytes(StandardCharsets.US_ASCII));
        writeChunk(out, iend.toByteArray());
        Path file = dir.resolve("bomb.png");
        Files.write(file, out.toByteArray());
        return file;
    }

    private static void writeChunk(ByteArrayOutputStream out, byte[] typeAndData) throws Exception {
        DataOutputStream d = new DataOutputStream(out);
        d.writeInt(typeAndData.length - 4);
        d.write(typeAndData);
        CRC32 crc = new CRC32();
        crc.update(typeAndData);
        d.writeInt((int) crc.getValue());
    }

    /** A real .docx whose main part is padded with 30 MB of spaces: compresses ~1000:1. */
    private Path docxBomb() throws Exception {
        Path plain = dir.resolve("plain.docx");
        Document doc = new Document();
        doc.newPage().add(Paragraph.body("hello"));
        new DocxDocumentWriter().write(doc, plain);
        Path bomb = dir.resolve("bomb.docx");
        try (ZipFile in = new ZipFile(plain.toFile()); ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(bomb))) {
            for (ZipEntry e : in.stream().toList()) {
                out.putNextEntry(new ZipEntry(e.getName()));
                in.getInputStream(e).transferTo(out);
                if (e.getName().equals("word/document.xml")) {
                    out.write(" ".repeat(30 * 1024 * 1024).getBytes(StandardCharsets.US_ASCII)); // trailing whitespace is valid XML
                }
                out.closeEntry();
            }
        }
        assertTrue(Files.size(bomb) < 1024 * 1024, "the bomb itself is small");
        return bomb;
    }

    @Test
    void imageBombsAreRefusedBeforeDecoding() throws Exception {
        Path bomb = pngBomb(60_000, 60_000);
        assertTrue(Files.size(bomb) < 100);
        ConversionException e = assertThrows(ConversionException.class, () -> ImageLoader.read(bomb));
        assertTrue(e.getMessage().contains("too large"), e.getMessage());
        assertDoesNotThrow(() -> Limits.checkImage(image(dir, "ok.png", photo(50, 50))));
    }

    @Test
    void renderResolutionIsCapped() {
        float dpi = Limits.renderDpi(14_400, 14_400, 300); // a 200-inch square page
        double pixels = Math.pow(14_400 / 72.0 * dpi, 2);
        assertTrue(pixels <= Limits.MAX_RENDER_PIXELS * 1.01, "capped to ~50 MP, got " + pixels);
        assertEquals(150f, Limits.renderDpi(595, 842, 150), 0.01f, "normal pages keep the requested DPI");
    }

    // ---- server --------------------------------------------------------------

    private void start(ServerConfig config, OcrService ocr) {
        server = new WebServer(config, new Converter(), ocr).start();
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + server.port() + path);
    }

    private HttpResponse<byte[]> post(String path, List<Path> files, Map<String, String> fields) throws Exception {
        String boundary = "----docsmelt" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (var f : fields.entrySet()) {
            body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + f.getKey() + "\"\r\n\r\n"
                    + f.getValue() + "\r\n").getBytes(StandardCharsets.UTF_8));
        }
        for (Path file : files) {
            body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"files\"; filename=\""
                    + file.getFileName() + "\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            body.write(Files.readAllBytes(file));
            body.write("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        body.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return http.send(HttpRequest.newBuilder(uri(path))
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String text(HttpResponse<byte[]> r) {
        return new String(r.body(), StandardCharsets.UTF_8);
    }

    private static String jobId(HttpResponse<byte[]> r) {
        Matcher m = Pattern.compile("\"id\":\"([0-9a-f-]{36})\"").matcher(text(r));
        assertTrue(m.find(), text(r));
        return m.group(1);
    }

    private String waitFor(String id, Set<String> states, int seconds) throws Exception {
        long until = System.currentTimeMillis() + seconds * 1000L;
        String status = "";
        while (System.currentTimeMillis() < until) {
            status = get("/api/jobs/" + id).body();
            for (String s : states) {
                if (status.contains("\"state\":\"" + s + "\"")) {
                    return status;
                }
            }
            Thread.sleep(100);
        }
        fail("job never reached " + states + ", last status " + status);
        return status;
    }

    /** OCR that takes {@code seconds} per page and stops when interrupted, like the real one. */
    private static final class SlowOcr extends FakeOcrEngine {
        private final long millis;

        SlowOcr(long millis) {
            super(true);
            this.millis = millis;
        }

        @Override
        public void recognize(List<Path> images, String languages, int dpi, Path outputBase, Set<Output> outputs) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ConversionException("Cancelled");
            }
            super.recognize(images, languages, dpi, outputBase, outputs);
        }
    }

    @Test
    void uploadsAreCheckedForBombsAndPageCounts() throws Exception {
        start(new ServerConfig("127.0.0.1", 0, 10, 5, 1000, 2, 3, 60, 10), new OcrService(new FakeOcrEngine(true)));

        var tooManyPages = post("/api/compress", List.of(pdf(dir, "long.pdf", "L", 5)), Map.of());
        assertEquals(400, tooManyPages.statusCode());
        assertTrue(text(tooManyPages).contains("has 5 pages; the limit here is 3"), text(tooManyPages));

        var image = post("/api/images-to-pdf", List.of(pngBomb(60_000, 60_000)), Map.of());
        assertEquals(400, image.statusCode());
        assertTrue(text(image).contains("too large"), text(image));

        var zip = post("/api/convert", List.of(docxBomb()), Map.of("to", "pdf"));
        assertEquals(400, zip.statusCode(), "zip bomb refused");
    }

    @Test
    void asyncJobReportsProgressAndHandsOverTheResultOnce() throws Exception {
        start(new ServerConfig("127.0.0.1", 0, 10, 5, 1000, 2), new OcrService(new SlowOcr(150)));

        var accepted = post("/api/ocr", List.of(pdf(dir, "scan.pdf", "S", 4)), Map.of("async", "true", "output", "txt"));
        assertEquals(202, accepted.statusCode(), text(accepted));
        String id = jobId(accepted);

        String running = waitFor(id, Set.of("running"), 10);
        assertTrue(running.contains("\"total\":4") || running.contains("\"total\":0"), running);
        String done = waitFor(id, Set.of("done", "failed"), 20);
        assertTrue(done.contains("\"filename\":\"scan.txt\""), done);

        HttpResponse<String> result = get("/api/jobs/" + id + "/result");
        assertEquals(200, result.statusCode());
        assertTrue(result.headers().firstValue("Content-Disposition").orElseThrow().contains("scan.txt"));
        assertTrue(result.body().contains("Recognized text 4"), result.body());
        assertEquals(404, get("/api/jobs/" + id + "/result").statusCode(), "results are deleted after download");
        assertEquals(404, get("/api/jobs/" + UUID.randomUUID()).statusCode());
    }

    @Test
    void slowJobsHitTheTimeLimit() throws Exception {
        start(new ServerConfig("127.0.0.1", 0, 10, 5, 1000, 2, 1000, 1, 10), new OcrService(new SlowOcr(30_000)));
        Path scan = pdf(dir, "scan.pdf", "S", 1);

        String id = jobId(post("/api/ocr", List.of(scan), Map.of("async", "true")));
        String failed = waitFor(id, Set.of("failed"), 15);
        assertTrue(failed.contains("took too long"), failed);

        long t0 = System.currentTimeMillis();
        var sync = post("/api/ocr", List.of(scan), Map.of());
        assertEquals(503, sync.statusCode(), text(sync));
        assertTrue(System.currentTimeMillis() - t0 < 15_000, "the waiting request is released too");
    }

    @Test
    void fullQueueIsRefusedAndJobsCanBeCancelled() throws Exception {
        start(new ServerConfig("127.0.0.1", 0, 10, 5, 1000, 1, 1000, 60, 1), new OcrService(new SlowOcr(5_000)));
        Path scan = pdf(dir, "scan.pdf", "S", 1);

        String running = jobId(post("/api/ocr", List.of(scan), Map.of("async", "true")));
        waitFor(running, Set.of("running"), 5);
        String queued = jobId(post("/api/ocr", List.of(scan), Map.of("async", "true")));
        var refused = post("/api/ocr", List.of(scan), Map.of("async", "true"));
        assertEquals(503, refused.statusCode(), text(refused));
        assertTrue(text(refused).contains("busy"));

        for (String id : List.of(queued, running)) {
            var deleted = http.send(HttpRequest.newBuilder(uri("/api/jobs/" + id)).DELETE().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(204, deleted.statusCode());
            assertEquals(404, get("/api/jobs/" + id).statusCode());
        }
        // The slot frees up: new work is accepted again.
        assertEquals(202, post("/api/ocr", List.of(scan), Map.of("async", "true")).statusCode());
    }
}

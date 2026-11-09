package com.docsmelt;

import com.docsmelt.core.Converter;
import com.docsmelt.ocr.OcrService;
import com.docsmelt.web.ServerConfig;
import com.docsmelt.web.WebServer;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.docsmelt.TestFiles.*;
import static org.junit.jupiter.api.Assertions.*;

class NewWebToolsTest {

    @TempDir
    static Path dir;

    private static WebServer server;
    private static final HttpClient http = HttpClient.newHttpClient();

    @BeforeAll
    static void start() {
        server = new WebServer(new ServerConfig("127.0.0.1", 0, 10, 5, 1000, 2), new Converter(),
                new OcrService(new FakeOcrEngine(true))).start();
    }

    @AfterAll
    static void stop() {
        server.close();
    }

    private static HttpResponse<byte[]> post(String path, List<Path> files, Map<String, String> fields) throws Exception {
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
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    private static String text(HttpResponse<byte[]> r) {
        return new String(r.body(), StandardCharsets.UTF_8);
    }

    private static String pdfText(byte[] pdf) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    private static int pages(byte[] pdf) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return doc.getNumberOfPages();
        }
    }

    @Test
    void formatsReportOcr() throws Exception {
        var r = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/api/formats")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertTrue(r.body().contains("\"ocr\":{\"available\":true,\"languages\":[\"eng\",\"swa\"]"), r.body());
    }

    @Test
    void previewThenOrganize() throws Exception {
        Path src = pdf(dir, "doc.pdf", "D", 3);

        var preview = post("/api/preview", List.of(src), Map.of());
        assertEquals(200, preview.statusCode(), text(preview));
        assertEquals(3, text(preview).split("data:image/jpeg;base64,").length - 1);
        assertTrue(text(preview).contains("\"rotation\":0"));

        var organized = post("/api/organize", List.of(src), Map.of("order", "3,1", "rotate", "3:90"));
        assertEquals(200, organized.statusCode(), text(organized));
        assertTrue(organized.headers().firstValue("Content-Disposition").orElseThrow().contains("doc-organized.pdf"));
        assertEquals(2, pages(organized.body()));
        String t = pdfText(organized.body());
        assertTrue(t.indexOf("D page 3") < t.indexOf("D page 1"), t);

        assertEquals(400, post("/api/organize", List.of(src), Map.of()).statusCode());
        assertEquals(400, post("/api/organize", List.of(src), Map.of("order", "1,9")).statusCode());
        assertEquals(400, post("/api/preview", List.of(image(dir, "p.png", photo(10, 10))), Map.of()).statusCode());
    }

    @Test
    void compressAndImagesToPdf() throws Exception {
        Path bloated = bloatedPdf(dir);
        var small = post("/api/compress", List.of(bloated), Map.of("level", "high"));
        assertEquals(200, small.statusCode(), text(small));
        assertTrue(small.body().length < Files.size(bloated) / 2);
        assertEquals(400, post("/api/compress", List.of(bloated), Map.of("level", "extreme")).statusCode());

        Path a = image(dir, "a.png", photo(300, 200));
        Path b = image(dir, "b.jpg", photo(200, 300));
        var pdf = post("/api/images-to-pdf", List.of(a, b), Map.of("pageSize", "letter", "margin", "none"));
        assertEquals(200, pdf.statusCode(), text(pdf));
        assertEquals(2, pages(pdf.body()));
        assertEquals(400, post("/api/images-to-pdf", List.of(a), Map.of("margin", "huge")).statusCode());
    }

    @Test
    void ocrOutputsAndLimits() throws Exception {
        Path scan = pdf(dir, "scan.pdf", "S", 2);
        var txt = post("/api/ocr", List.of(scan), Map.of("output", "txt", "lang", "swa"));
        assertEquals(200, txt.statusCode(), text(txt));
        assertTrue(text(txt).contains("Recognized text 2"));

        var searchable = post("/api/ocr", List.of(scan), Map.of("output", "pdf"));
        assertTrue(searchable.headers().firstValue("Content-Disposition").orElseThrow().contains("scan-searchable.pdf"));

        assertEquals(400, post("/api/ocr", List.of(scan), Map.of("lang", "eng;ls")).statusCode());
        assertEquals(400, post("/api/ocr", List.of(scan), Map.of("output", "png")).statusCode());
        var tooLong = post("/api/ocr", List.of(pdf(dir, "long.pdf", "L", 31)), Map.of());
        assertEquals(400, tooLong.statusCode());
        assertTrue(text(tooLong).contains("limited to 30 pages"), text(tooLong));
    }

    @Test
    void protectUnlockNumberAndSign() throws Exception {
        Path src = pdf(dir, "deal.pdf", "Deal", 3);

        var locked = post("/api/protect", List.of(src), Map.of("password", "pass1234", "allowCopy", "false"));
        assertEquals(200, locked.statusCode(), text(locked));
        assertTrue(locked.headers().firstValue("Content-Disposition").orElseThrow().contains("deal-protected.pdf"));
        Path lockedFile = Files.write(dir.resolve("deal-protected.pdf"), locked.body());
        assertThrows(Exception.class, () -> Loader.loadPDF(lockedFile.toFile()).close());
        assertEquals(400, post("/api/protect", List.of(src), Map.of()).statusCode());

        var wrong = post("/api/unlock", List.of(lockedFile), Map.of("password", "nope"));
        assertEquals(400, wrong.statusCode());
        assertTrue(text(wrong).contains("Wrong password"));
        var unlocked = post("/api/unlock", List.of(lockedFile), Map.of("password", "pass1234"));
        assertEquals(200, unlocked.statusCode(), text(unlocked));
        assertTrue(pdfText(unlocked.body()).contains("Deal page 2"));

        var lockedPreview = post("/api/preview", List.of(lockedFile), Map.of());
        assertTrue(text(lockedPreview).contains("password-protected"), text(lockedPreview));

        var numbered = post("/api/number", List.of(src), Map.of("format", "Ukurasa {n} / {total}", "position", "top-right"));
        assertEquals(200, numbered.statusCode(), text(numbered));
        assertTrue(pdfText(numbered.body()).contains("Ukurasa 3 / 3"));
        assertEquals(400, post("/api/number", List.of(src), Map.of("format", "no number")).statusCode());

        var big = post("/api/preview", List.of(src), Map.of("page", "0", "width", "400"));
        assertTrue(text(big).startsWith("{\"count\":3,\"page\":3,"), text(big).substring(0, 40));

        Path sig = image(dir, "sig.png", photo(120, 40));
        var signed = post("/api/sign", List.of(sig, src), Map.of("page", "2", "x", "0.1", "y", "0.1", "width", "0.3"));
        assertEquals(200, signed.statusCode(), text(signed));
        assertEquals(3, pages(signed.body()));
        assertEquals(400, post("/api/sign", List.of(src, sig), Map.of("x", "2", "y", "0", "width", "0.3")).statusCode());
        assertEquals(400, post("/api/sign", List.of(src), Map.of()).statusCode());
    }

    @Test
    void officeFormatsConvert() throws Exception {
        var formats = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/api/formats")).build(),
                HttpResponse.BodyHandlers.ofString()).body();
        assertTrue(formats.contains("\"xlsx\"") && formats.contains("\"pptx\"") && formats.contains("\"xls\""), formats);

        Path txt = dir.resolve("list.txt");
        Files.writeString(txt, "# Shopping\n\nMilk\n\nBread");
        var xlsx = post("/api/convert", List.of(txt), Map.of("to", "xlsx"));
        assertEquals(200, xlsx.statusCode(), text(xlsx));
        var pptx = post("/api/convert", List.of(txt), Map.of("to", "pptx"));
        assertEquals(200, pptx.statusCode(), text(pptx));
        assertEquals("PK", new String(pptx.body(), 0, 2, StandardCharsets.US_ASCII), "a zip-based Office file");
    }
}

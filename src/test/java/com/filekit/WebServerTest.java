package com.filekit;

import com.filekit.core.Converter;
import com.filekit.io.pdf.PdfDocumentWriter;
import com.filekit.model.Document;
import com.filekit.model.Paragraph;
import com.filekit.web.ServerConfig;
import com.filekit.web.WebServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;

class WebServerTest {

    @TempDir
    static Path dir;

    private static WebServer server;
    private static final HttpClient http = HttpClient.newHttpClient();

    @BeforeAll
    static void start() {
        ServerConfig config = new ServerConfig("127.0.0.1", 0, 1, 3, 1000, 2);
        server = new WebServer(config, new Converter()).start();
    }

    @AfterAll
    static void stop() {
        server.close();
    }

    private static URI uri(String path) {
        return URI.create("http://127.0.0.1:" + server.port() + path);
    }

    private record Part(String filename, byte[] content) {
    }

    /** Minimal multipart/form-data encoder. */
    private static HttpResponse<byte[]> post(String path, List<Part> files, Map<String, String> fields) throws Exception {
        String boundary = "----filekit" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (var field : fields.entrySet()) {
            body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + field.getKey()
                    + "\"\r\n\r\n" + field.getValue() + "\r\n").getBytes(StandardCharsets.UTF_8));
        }
        for (Part part : files) {
            body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"files\"; filename=\""
                    + part.filename() + "\"\r\nContent-Type: application/octet-stream\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            body.write(part.content());
            body.write("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        body.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        HttpRequest request = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofByteArray());
    }

    private static byte[] pdf(String prefix, int pages) throws IOException {
        Document doc = new Document();
        for (int i = 1; i <= pages; i++) {
            doc.newPage().add(Paragraph.body(prefix + " " + i));
        }
        Path file = Files.createTempFile(dir, "p", ".pdf");
        new PdfDocumentWriter().write(doc, file);
        return Files.readAllBytes(file);
    }

    private static List<String> zipEntries(byte[] zip) throws IOException {
        List<String> names = new ArrayList<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
                names.add(e.getName());
            }
        }
        return names;
    }

    private static String text(HttpResponse<byte[]> res) {
        return new String(res.body(), StandardCharsets.UTF_8);
    }

    @Test
    void servesPageAndFormats() throws Exception {
        var page = http.send(HttpRequest.newBuilder(uri("/")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, page.statusCode());
        assertTrue(page.body().contains("<title>filekit</title>"));
        assertTrue(page.headers().firstValue("Content-Security-Policy").isPresent());

        var formats = http.send(HttpRequest.newBuilder(uri("/api/formats")).build(), HttpResponse.BodyHandlers.ofString());
        assertTrue(formats.body().contains("\"write\":[") && formats.body().contains("\"maxUploadMb\":1"), formats.body());
    }

    @Test
    void convertsOneFileToADirectDownload() throws Exception {
        var res = post("/api/convert", List.of(new Part("notes.txt", "# Hi\n\nhello".getBytes())), Map.of("to", "pdf"));
        assertEquals(200, res.statusCode(), text(res));
        assertEquals("application/pdf", res.headers().firstValue("Content-Type").orElseThrow());
        assertTrue(res.headers().firstValue("Content-Disposition").orElseThrow().contains("notes.pdf"));
        assertEquals("%PDF", new String(res.body(), 0, 4, StandardCharsets.US_ASCII));
    }

    @Test
    void multipleOutputsComeBackAsZipWithUniqueNames() throws Exception {
        var res = post("/api/convert", List.of(
                new Part("a.txt", "one".getBytes()),
                new Part("a.txt", "two".getBytes())), Map.of("to", "docx"));
        assertEquals(200, res.statusCode(), text(res));
        assertEquals("application/zip", res.headers().firstValue("Content-Type").orElseThrow());
        assertEquals(List.of("a.docx", "a (2).docx"), zipEntries(res.body()));
    }

    @Test
    void mergeAndSplit() throws Exception {
        var merged = post("/api/merge", List.of(new Part("x.pdf", pdf("X", 2)), new Part("y.pdf", pdf("Y", 3))), Map.of());
        assertEquals(200, merged.statusCode(), text(merged));

        var split = post("/api/split", List.of(new Part("merged.pdf", merged.body())), Map.of("ranges", "1-2,3-"));
        assertEquals(200, split.statusCode(), text(split));
        assertEquals(List.of("merged-1.pdf", "merged-2.pdf"), zipEntries(split.body()));
    }

    @Test
    void errorsAreJsonWithUsefulMessages() throws Exception {
        var noFormat = post("/api/convert", List.of(new Part("a.txt", "x".getBytes())), Map.of());
        assertEquals(400, noFormat.statusCode());
        assertTrue(text(noFormat).contains("\"error\":\"Please choose an output format\""), text(noFormat));

        var unknown = post("/api/convert", List.of(new Part("a.xyz", "x".getBytes())), Map.of("to", "pdf"));
        assertEquals(400, unknown.statusCode());
        assertTrue(text(unknown).contains("Unsupported file type"), text(unknown));

        var corrupt = post("/api/convert", List.of(new Part("bad.pdf", "nope".getBytes())), Map.of("to", "png"));
        assertEquals(400, corrupt.statusCode());
        assertTrue(text(corrupt).contains("bad.pdf"), text(corrupt));

        var tooMany = post("/api/convert", List.of(
                new Part("1.txt", "x".getBytes()), new Part("2.txt", "x".getBytes()),
                new Part("3.txt", "x".getBytes()), new Part("4.txt", "x".getBytes())), Map.of("to", "pdf"));
        assertEquals(400, tooMany.statusCode());

        var tooBig = post("/api/convert", List.of(new Part("big.txt", new byte[2 * 1024 * 1024])), Map.of("to", "pdf"));
        assertTrue(tooBig.statusCode() == 400 || tooBig.statusCode() == 413, "status " + tooBig.statusCode());

        var mergeOne = post("/api/merge", List.of(new Part("x.pdf", pdf("X", 1))), Map.of());
        assertEquals(400, mergeOne.statusCode());
    }

    @Test
    void pathTraversalInFilenameStaysInsideWorkspace() throws Exception {
        var res = post("/api/convert", List.of(new Part("../../../../tmp/evil.txt", "x".getBytes())), Map.of("to", "pdf"));
        assertEquals(200, res.statusCode(), text(res));
        assertTrue(res.headers().firstValue("Content-Disposition").orElseThrow().contains("filename=\"evil.pdf\""));
    }
}

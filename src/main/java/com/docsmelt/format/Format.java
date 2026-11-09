package com.docsmelt.format;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** File formats docsmelt knows about, identified by extension. */
public enum Format {
    PDF(Kind.DOCUMENT, "pdf"),
    DOCX(Kind.DOCUMENT, "docx"),
    TXT(Kind.DOCUMENT, "txt"),
    PNG(Kind.IMAGE, "png"),
    JPEG(Kind.IMAGE, "jpg", "jpeg"),
    BMP(Kind.IMAGE, "bmp"),
    GIF(Kind.IMAGE, "gif");

    public enum Kind { DOCUMENT, IMAGE }

    private final Kind kind;
    private final List<String> extensions;

    Format(Kind kind, String... extensions) {
        this.kind = kind;
        this.extensions = List.of(extensions);
    }

    public Kind kind() {
        return kind;
    }

    public boolean isImage() {
        return kind == Kind.IMAGE;
    }

    public List<String> extensions() {
        return extensions;
    }

    /** The extension used when this format is written. */
    public String primaryExtension() {
        return extensions.getFirst();
    }

    public static Format fromPath(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            throw new UnsupportedFormatException("File has no extension: " + path);
        }
        String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(f -> f.extensions.contains(ext))
                .findFirst()
                .orElseThrow(() -> new UnsupportedFormatException("Unsupported file type: ." + ext));
    }
}

package com.filekit.io.text;

import com.filekit.io.ConversionException;
import com.filekit.io.DocumentReader;
import com.filekit.io.ReadOptions;
import com.filekit.model.Document;
import com.filekit.model.Page;
import com.filekit.model.Paragraph;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads UTF-8 text. Blank lines separate paragraphs; a form-feed character
 * starts a new page; a line starting with "# " is a heading.
 */
public final class TextDocumentReader implements DocumentReader {

    @Override
    public Document read(Path source, ReadOptions options) {
        try {
            String content = Files.readString(source, StandardCharsets.UTF_8);
            Document document = new Document();
            for (String pageText : content.split("\f")) {
                Page page = document.newPage();
                for (String chunk : pageText.split("\\R\\s*\\R")) {
                    String text = chunk.strip();
                    if (text.startsWith("# ")) {
                        page.add(Paragraph.heading(text.substring(2).strip()));
                    } else if (!text.isEmpty()) {
                        page.add(Paragraph.body(text.replaceAll("\\s+", " ")));
                    }
                }
            }
            return options.pages().select(document);
        } catch (IOException e) {
            throw new ConversionException("Could not read text file " + source + ": " + e.getMessage(), e);
        }
    }
}

package com.filekit.io.text;

import com.filekit.io.ConversionException;
import com.filekit.io.DocumentWriter;
import com.filekit.io.WriteOptions;
import com.filekit.model.Block;
import com.filekit.model.Document;
import com.filekit.model.ImageBlock;
import com.filekit.model.Page;
import com.filekit.model.Paragraph;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Writes the mirror image of {@link TextDocumentReader}'s conventions. Images become placeholders. */
public final class TextDocumentWriter implements DocumentWriter {

    @Override
    public List<Path> write(Document document, Path target, WriteOptions options) {
        List<String> pages = new ArrayList<>();
        for (Page page : document.pages()) {
            List<String> parts = new ArrayList<>();
            for (Block block : page.blocks()) {
                parts.add(switch (block) {
                    case Paragraph p when p.isHeading() -> "# " + p.text();
                    case Paragraph p -> p.text();
                    case ImageBlock img -> "[image " + img.width() + "x" + img.height() + "]";
                });
            }
            pages.add(String.join("\n\n", parts));
        }
        try {
            Files.writeString(target, String.join("\n\f\n", pages) + "\n", StandardCharsets.UTF_8);
            return List.of(target);
        } catch (IOException e) {
            throw new ConversionException("Could not write text file " + target + ": " + e.getMessage(), e);
        }
    }
}

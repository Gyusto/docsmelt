package com.docsmelt.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

/**
 * Format-independent, in-memory representation of a document.
 *
 * <p>Every reader converts <em>into</em> this model and every writer converts
 * <em>out of</em> it, so N formats need N readers + N writers instead of N×N
 * direct converters.
 */
public final class Document {

    private final List<Page> pages = new ArrayList<>();

    public Page newPage() {
        Page page = new Page();
        pages.add(page);
        return page;
    }

    public Document addPage(Page page) {
        pages.add(page);
        return this;
    }

    public List<Page> pages() {
        return Collections.unmodifiableList(pages);
    }

    public Stream<Block> blocks() {
        return pages.stream().flatMap(p -> p.blocks().stream());
    }

    /** All paragraph text, separated by blank lines. Handy for tests and text export. */
    public String plainText() {
        return blocks()
                .filter(Paragraph.class::isInstance)
                .map(b -> ((Paragraph) b).text())
                .reduce((a, b) -> a + "\n\n" + b)
                .orElse("");
    }
}

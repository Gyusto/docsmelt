package com.filekit.io;

import com.filekit.model.Document;
import com.filekit.model.Page;

import java.util.ArrayList;
import java.util.List;

/**
 * A 1-based page selection such as {@code "3"}, {@code "2-5"}, {@code "1,4,7-"}
 * (open end = to the last page).
 */
public final class PageRange {

    public static final PageRange ALL = new PageRange(List.of(), "all");

    private record Span(int from, int to) {
        boolean includes(int page) {
            return page >= from && page <= to;
        }
    }

    private final List<Span> spans;
    private final String spec;

    private PageRange(List<Span> spans, String spec) {
        this.spans = spans;
        this.spec = spec;
    }

    public static PageRange parse(String spec) {
        if (spec == null || spec.isBlank() || spec.equalsIgnoreCase("all")) {
            return ALL;
        }
        List<Span> spans = new ArrayList<>();
        for (String part : spec.split(",")) {
            String p = part.strip();
            try {
                int dash = p.indexOf('-');
                if (dash < 0) {
                    int n = Integer.parseInt(p);
                    spans.add(new Span(n, n));
                } else {
                    int from = dash == 0 ? 1 : Integer.parseInt(p.substring(0, dash).strip());
                    String rest = p.substring(dash + 1).strip();
                    int to = rest.isEmpty() ? Integer.MAX_VALUE : Integer.parseInt(rest);
                    spans.add(new Span(from, to));
                }
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid page range '" + spec + "' (use e.g. 1,3,5-8)");
            }
            Span last = spans.getLast();
            if (last.from() < 1 || last.to() < last.from()) {
                throw new IllegalArgumentException("Invalid page range '" + p + "' in '" + spec + "'");
            }
        }
        return new PageRange(List.copyOf(spans), spec.strip());
    }

    public boolean isAll() {
        return spans.isEmpty();
    }

    /** @param pageNumber 1-based */
    public boolean includes(int pageNumber) {
        return isAll() || spans.stream().anyMatch(s -> s.includes(pageNumber));
    }

    /** Returns a document holding only the selected pages; fails if none match. */
    public Document select(Document document) {
        if (isAll()) {
            return document;
        }
        Document selected = new Document();
        List<Page> pages = document.pages();
        for (int i = 0; i < pages.size(); i++) {
            if (includes(i + 1)) {
                selected.addPage(pages.get(i));
            }
        }
        requireAny(selected.pages().size(), pages.size());
        return selected;
    }

    /** Fails with a helpful message when a selection matched nothing. */
    public void requireAny(int selectedCount, int totalPages) {
        if (selectedCount == 0) {
            throw new ConversionException("No pages match '" + spec + "' (document has " + totalPages + " page"
                    + (totalPages == 1 ? "" : "s") + ")");
        }
    }

    @Override
    public String toString() {
        return spec;
    }
}

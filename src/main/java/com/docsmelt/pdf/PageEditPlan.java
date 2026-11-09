package com.docsmelt.pdf;

import com.docsmelt.io.ConversionException;
import com.docsmelt.io.PageRange;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

/**
 * Describes how to rearrange a PDF: which pages to keep, in which order, and
 * how far to rotate each. Built from user input, then {@link #resolve resolved}
 * against the real page count.
 *
 * <p>Order of application: start from {@code order} (or 1..n), drop deleted
 * pages, apply rotations. Page numbers always refer to the <em>original</em> document.
 */
public final class PageEditPlan {

    /** One output page: which original page it is, and the rotation to add to it. */
    public record PageSpec(int sourcePage, int extraRotation) {
    }

    private record Rotation(int degrees, PageRange pages) {
    }

    private List<Integer> order;
    private PageRange delete;
    private final List<Rotation> rotations = new ArrayList<>();

    /** Output order as original page numbers, e.g. [3, 1, 2]. Pages not listed are dropped. */
    public PageEditPlan order(List<Integer> pages) {
        if (pages.isEmpty()) {
            throw new IllegalArgumentException("Page order is empty");
        }
        if (new HashSet<>(pages).size() != pages.size()) {
            throw new IllegalArgumentException("Page order lists a page twice");
        }
        this.order = List.copyOf(pages);
        return this;
    }

    public PageEditPlan delete(PageRange pages) {
        this.delete = pages;
        return this;
    }

    /** Rotate clockwise; degrees must be a multiple of 90 (negative = counter-clockwise). */
    public PageEditPlan rotate(int degrees, PageRange pages) {
        if (degrees % 90 != 0) {
            throw new IllegalArgumentException("Rotation must be a multiple of 90 degrees, got " + degrees);
        }
        rotations.add(new Rotation(Math.floorMod(degrees, 360), pages));
        return this;
    }

    public boolean isEmpty() {
        return order == null && delete == null && rotations.isEmpty();
    }

    public List<PageSpec> resolve(int pageCount) {
        List<Integer> pages = order != null ? order : IntStream.rangeClosed(1, pageCount).boxed().toList();
        for (int p : pages) {
            if (p < 1 || p > pageCount) {
                throw new ConversionException("Page " + p + " does not exist (document has " + pageCount + " pages)");
            }
        }
        List<PageSpec> result = new ArrayList<>();
        for (int p : pages) {
            if (delete != null && delete.includes(p)) {
                continue;
            }
            int rotation = 0;
            for (Rotation r : rotations) {
                if (r.pages().includes(p)) {
                    rotation += r.degrees();
                }
            }
            result.add(new PageSpec(p, rotation % 360));
        }
        if (result.isEmpty()) {
            throw new ConversionException("These changes would remove every page");
        }
        return result;
    }

    /** Parses "1,4,2" into page numbers. */
    public static List<Integer> parseOrder(String spec) {
        List<Integer> pages = new ArrayList<>();
        for (String part : spec.split(",")) {
            try {
                pages.add(Integer.parseInt(part.strip()));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid page order '" + spec + "' (use e.g. 3,1,2)");
            }
        }
        return pages;
    }

    /** Parses "90", "90:1-3" or "-90:2" style rotation specs (default range: all pages). */
    public PageEditPlan rotate(String spec) {
        String[] parts = spec.split(":", 2);
        try {
            int degrees = Integer.parseInt(parts[0].strip());
            return rotate(degrees, parts.length == 2 ? PageRange.parse(parts[1]) : PageRange.ALL);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid rotation '" + spec + "' (use e.g. 90 or 90:1-3)");
        }
    }

    /** Parses the web UI's "1:90,4:270" per-page rotation list. */
    public PageEditPlan rotatePerPage(String spec) {
        Set<Integer> seen = new HashSet<>();
        for (String part : spec.split(",")) {
            if (part.isBlank()) {
                continue;
            }
            String[] kv = part.split(":", 2);
            try {
                int page = Integer.parseInt(kv[0].strip());
                if (!seen.add(page) || kv.length != 2) {
                    throw new NumberFormatException();
                }
                rotate(Integer.parseInt(kv[1].strip()), PageRange.parse(String.valueOf(page)));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid rotations '" + spec + "' (use e.g. 1:90,4:270)");
            }
        }
        return this;
    }
}

package com.docsmelt.pdf;

import com.docsmelt.io.ConversionException;
import com.docsmelt.io.PageRange;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

/**
 * Decides how a PDF's pages are grouped into output files.
 * Each group is a list of 1-based page numbers.
 */
public sealed interface SplitStrategy {

    List<List<Integer>> groups(int pageCount);

    /** Chunks of {@code n} pages: n=1 gives one file per page. */
    record EveryNPages(int n) implements SplitStrategy {
        public EveryNPages {
            if (n < 1) {
                throw new IllegalArgumentException("--every must be at least 1");
            }
        }

        @Override
        public List<List<Integer>> groups(int pageCount) {
            List<List<Integer>> groups = new ArrayList<>();
            for (int start = 1; start <= pageCount; start += n) {
                groups.add(IntStream.rangeClosed(start, Math.min(start + n - 1, pageCount)).boxed().toList());
            }
            return groups;
        }
    }

    /** One output file per range, e.g. {@code "1-3,4,5-"} → 3 files. */
    record ByRanges(List<PageRange> ranges) implements SplitStrategy {

        public static ByRanges parse(String spec) {
            List<PageRange> ranges = new ArrayList<>();
            for (String part : spec.split(",")) {
                ranges.add(PageRange.parse(part));
            }
            return new ByRanges(List.copyOf(ranges));
        }

        @Override
        public List<List<Integer>> groups(int pageCount) {
            List<List<Integer>> groups = new ArrayList<>();
            for (PageRange range : ranges) {
                List<Integer> pages = IntStream.rangeClosed(1, pageCount).filter(range::includes).boxed().toList();
                if (pages.isEmpty()) {
                    throw new ConversionException("Range '" + range + "' is outside the document ("
                            + pageCount + " pages)");
                }
                groups.add(pages);
            }
            return groups;
        }
    }
}

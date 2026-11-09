package com.docsmelt.io;

import java.util.Objects;

/**
 * Settings passed to a {@link DocumentReader}.
 *
 * @param renderSnapshots whether readers that can rasterize pages should do so
 *                        (expensive; only image writers want it)
 * @param dpi             resolution used for snapshots
 * @param pages           which pages to read; readers must honour it
 */
public record ReadOptions(boolean renderSnapshots, int dpi, PageRange pages) {

    public static final ReadOptions DEFAULT = new ReadOptions(false, 150, PageRange.ALL);

    public ReadOptions {
        Objects.requireNonNull(pages, "pages");
    }
}

package com.docsmelt.model;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * One page of a {@link Document}.
 *
 * <p>Besides its structured {@link Block}s a page may carry a <em>snapshot</em>:
 * a faithful raster of how the page looked in its source format (e.g. a rendered
 * PDF page). Writers that output images prefer the snapshot; writers that need
 * structure (Word, PDF, text) use the blocks.
 */
public final class Page {

    private final List<Block> blocks = new ArrayList<>();
    private BufferedImage snapshot;

    public Page add(Block block) {
        blocks.add(block);
        return this;
    }

    public List<Block> blocks() {
        return Collections.unmodifiableList(blocks);
    }

    public Optional<BufferedImage> snapshot() {
        return Optional.ofNullable(snapshot);
    }

    public Page withSnapshot(BufferedImage snapshot) {
        this.snapshot = snapshot;
        return this;
    }

    public boolean isEmpty() {
        return blocks.isEmpty() && snapshot == null;
    }
}

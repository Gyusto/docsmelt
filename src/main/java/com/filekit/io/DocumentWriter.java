package com.filekit.io;

import com.filekit.model.Document;

import java.nio.file.Path;
import java.util.List;

/** Strategy for writing the common {@link Document} model to one file format. */
public interface DocumentWriter {

    /**
     * Writes the document.
     *
     * @return every file actually produced (an image writer may emit one file per page)
     */
    List<Path> write(Document document, Path target, WriteOptions options);

    default List<Path> write(Document document, Path target) {
        return write(document, target, WriteOptions.DEFAULT);
    }

    /**
     * Whether readers should render page snapshots for this writer. Only writers
     * that output pictures of pages benefit, and rendering is expensive.
     */
    default boolean wantsSnapshots() {
        return false;
    }
}

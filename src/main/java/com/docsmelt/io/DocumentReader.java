package com.docsmelt.io;

import com.docsmelt.model.Document;

import java.nio.file.Path;

/** Strategy for turning a file of one format into the common {@link Document} model. */
public interface DocumentReader {

    Document read(Path source, ReadOptions options);

    default Document read(Path source) {
        return read(source, ReadOptions.DEFAULT);
    }
}

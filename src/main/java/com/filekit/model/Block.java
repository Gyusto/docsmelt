package com.filekit.model;

/**
 * A unit of content inside a {@link Page}. Sealed so every reader/writer can
 * handle all block kinds exhaustively with a pattern-matching switch.
 */
public sealed interface Block permits Paragraph, ImageBlock {
}

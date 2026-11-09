package com.docsmelt.model;

import java.util.Objects;

/** A run of text with a simple semantic style. */
public record Paragraph(String text, Style style) implements Block {

    public enum Style { HEADING, BODY }

    public Paragraph {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(style, "style");
    }

    public static Paragraph body(String text) {
        return new Paragraph(text, Style.BODY);
    }

    public static Paragraph heading(String text) {
        return new Paragraph(text, Style.HEADING);
    }

    public boolean isHeading() {
        return style == Style.HEADING;
    }
}

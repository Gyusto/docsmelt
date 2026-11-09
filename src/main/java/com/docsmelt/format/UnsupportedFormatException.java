package com.docsmelt.format;

import com.docsmelt.io.ConversionException;

public class UnsupportedFormatException extends ConversionException {
    public UnsupportedFormatException(String message) {
        super(message);
    }
}

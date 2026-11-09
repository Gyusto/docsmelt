package com.filekit.format;

import com.filekit.io.ConversionException;

public class UnsupportedFormatException extends ConversionException {
    public UnsupportedFormatException(String message) {
        super(message);
    }
}

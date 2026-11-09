package com.docsmelt.cli;

import com.docsmelt.io.ConversionException;
import com.docsmelt.io.PageRange;
import com.docsmelt.ocr.OcrService;
import com.docsmelt.ocr.TesseractEngine;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "ocr", mixinStandardHelpOptions = true, sortOptions = false,
        description = "Recognize text in scanned PDFs and photos (needs Tesseract). "
                + "The output type follows the extension: .pdf (searchable), .txt or .docx.",
        footer = {"", "Examples:",
                "  docsmelt ocr scan.pdf -o searchable.pdf",
                "  docsmelt ocr receipt.jpg -o receipt.txt",
                "  docsmelt ocr barua.pdf -o barua.docx --lang swa+eng",
                "  docsmelt ocr --list-langs"})
final class OcrCommand implements Callable<Integer> {

    @Parameters(paramLabel = "FILE", arity = "0..1", description = "Scanned PDF or image.")
    Path input;

    @Option(names = {"-o", "--output"}, paramLabel = "FILE", description = "Result: .pdf, .txt or .docx.")
    Path output;

    @Option(names = "--lang", defaultValue = "eng",
            description = "Language(s), e.g. eng, swa, eng+fra. Default: ${DEFAULT-VALUE}.")
    String lang;

    @Option(names = "--pages", paramLabel = "RANGE", description = "PDF pages to read, e.g. 1-3.")
    String pages;

    @Option(names = "--dpi", defaultValue = "300", description = "Render resolution for PDFs. Default: ${DEFAULT-VALUE}.")
    int dpi;

    @Option(names = "--list-langs", description = "Show installed OCR languages and exit.")
    boolean listLangs;

    @Spec
    CommandSpec spec;

    @Override
    public Integer call() {
        OcrService ocr = new OcrService(new TesseractEngine());
        if (listLangs) {
            if (!ocr.engine().isAvailable()) {
                spec.commandLine().getErr().println("Tesseract is not installed (apt install tesseract-ocr).");
                return 1;
            }
            spec.commandLine().getOut().println(String.join(" ", ocr.engine().languages()));
            return 0;
        }
        if (input == null || output == null) {
            throw new CommandLine.ParameterException(spec.commandLine(), "need FILE and -o OUTPUT");
        }
        OcrService.Options options;
        try {
            options = new OcrService.Options(lang, dpi, PageRange.parse(pages), 0);
        } catch (IllegalArgumentException e) {
            throw new CommandLine.ParameterException(spec.commandLine(), e.getMessage());
        }
        try {
            ocr.recognize(input, output, options);
            spec.commandLine().getOut().println("Wrote " + output);
            return 0;
        } catch (ConversionException | IllegalArgumentException e) {
            spec.commandLine().getErr().println("error: " + e.getMessage());
            return 1;
        }
    }
}

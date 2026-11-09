package com.docsmelt.io.office;

import com.docsmelt.io.ConversionException;
import com.docsmelt.io.DocumentReader;
import com.docsmelt.io.ReadOptions;
import com.docsmelt.model.Document;
import com.docsmelt.model.Page;
import com.docsmelt.model.Paragraph;
import com.docsmelt.model.Table;
import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reads Excel workbooks (.xlsx and legacy .xls). Each visible sheet becomes a
 * page: a heading with the sheet name, then its used range as a {@link Table}
 * whose first row is the header. Values are shown as Excel displays them
 * (number formats, dates, cached formula results).
 */
public final class SpreadsheetReader implements DocumentReader {

    /** Keeps memory and output size sane for huge sheets. */
    static final int MAX_ROWS = 5_000;
    static final int MAX_COLUMNS = 50;

    @Override
    public Document read(Path source, ReadOptions options) {
        try (Workbook workbook = WorkbookFactory.create(source.toFile(), null, true)) {
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            formatter.setUseCachedValuesForFormulaCells(true); // never re-evaluate: shows what Excel saved
            Document document = new Document();
            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                if (workbook.isSheetHidden(i) || workbook.isSheetVeryHidden(i)) {
                    continue;
                }
                Sheet sheet = workbook.getSheetAt(i);
                Page page = document.newPage().add(Paragraph.heading(sheet.getSheetName()));
                readSheet(sheet, formatter, page);
            }
            if (document.pages().isEmpty()) {
                throw new ConversionException("The workbook has no visible sheets");
            }
            return options.pages().select(document);
        } catch (EncryptedDocumentException e) {
            throw new ConversionException("This spreadsheet is password-protected", e);
        } catch (IOException | RuntimeException e) {
            if (e instanceof ConversionException ce) {
                throw ce;
            }
            throw new ConversionException("Could not read spreadsheet " + source.getFileName() + ": " + e.getMessage(), e);
        }
    }

    private static void readSheet(Sheet sheet, DataFormatter formatter, Page page) {
        List<List<String>> rows = new ArrayList<>();
        int firstCol = Integer.MAX_VALUE;
        int lastCol = -1;
        int skipped = 0;
        for (Row row : sheet) {
            if (rows.size() >= MAX_ROWS) {
                skipped++;
                continue;
            }
            List<String> cells = new ArrayList<>();
            for (int c = 0; c < Math.min(Math.max(row.getLastCellNum(), 0), MAX_COLUMNS); c++) {
                Cell cell = row.getCell(c);
                String value = cell == null ? "" : formatter.formatCellValue(cell).strip();
                cells.add(value);
                if (!value.isEmpty()) {
                    firstCol = Math.min(firstCol, c);
                    lastCol = Math.max(lastCol, c);
                }
            }
            // Keep empty rows between data (they're part of the layout) but pad by index.
            while (rows.size() < row.getRowNum() - firstRowIndex(sheet) && rows.size() < MAX_ROWS) {
                rows.add(List.of());
            }
            rows.add(cells);
        }
        if (lastCol < 0) {
            page.add(Paragraph.body("(empty sheet)"));
            return;
        }
        // Trim to the used columns and drop trailing empty rows.
        List<List<String>> trimmed = new ArrayList<>();
        for (List<String> r : rows) {
            List<String> cut = new ArrayList<>();
            for (int c = firstCol; c <= lastCol; c++) {
                cut.add(c < r.size() ? r.get(c) : "");
            }
            trimmed.add(cut);
        }
        while (!trimmed.isEmpty() && trimmed.getLast().stream().allMatch(String::isEmpty)) {
            trimmed.removeLast();
        }
        while (!trimmed.isEmpty() && trimmed.getFirst().stream().allMatch(String::isEmpty)) {
            trimmed.removeFirst();
        }
        page.add(new Table(trimmed, trimmed.size() > 1));
        if (skipped > 0) {
            page.add(Paragraph.body("… " + skipped + " more rows not shown (limit " + MAX_ROWS + ")."));
        }
    }

    private static int firstRowIndex(Sheet sheet) {
        return Math.max(sheet.getFirstRowNum(), 0);
    }
}

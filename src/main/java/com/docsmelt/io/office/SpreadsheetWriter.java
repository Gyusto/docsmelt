package com.docsmelt.io.office;

import com.docsmelt.io.ConversionException;
import com.docsmelt.io.DocumentWriter;
import com.docsmelt.io.WriteOptions;
import com.docsmelt.model.Block;
import com.docsmelt.model.Document;
import com.docsmelt.model.ImageBlock;
import com.docsmelt.model.Page;
import com.docsmelt.model.Paragraph;
import com.docsmelt.model.Table;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.util.WorkbookUtil;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Writes an .xlsx workbook: one sheet per page. Tables become real cells
 * (numbers are stored as numbers, so sums and charts work), paragraphs go
 * into column A, headings and header rows are bold.
 */
public final class SpreadsheetWriter implements DocumentWriter {

    /** Plain numbers like 12, -3.5, 1,250,000 — but not ids such as 007 or phone numbers. */
    private static final Pattern NUMBER = Pattern.compile("-?(0|[1-9]\\d{0,2}(,\\d{3})+|[1-9]\\d*)(\\.\\d+)?");
    private static final int AUTOSIZE_COLUMNS = 30;

    @Override
    public List<Path> write(Document document, Path target, WriteOptions options) {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Styles styles = new Styles(workbook);
            Set<String> names = new HashSet<>();
            List<Page> pages = document.pages();
            for (int i = 0; i < pages.size(); i++) {
                Page page = pages.get(i);
                XSSFSheet sheet = workbook.createSheet(sheetName(page, i + 1, names));
                int widest = writePage(page, sheet, styles);
                for (int c = 0; c < Math.min(widest, AUTOSIZE_COLUMNS); c++) {
                    sheet.autoSizeColumn(c);
                    sheet.setColumnWidth(c, Math.min(sheet.getColumnWidth(c) + 512, 60 * 256));
                }
            }
            if (pages.isEmpty()) {
                workbook.createSheet("Sheet1");
            }
            try (OutputStream out = Files.newOutputStream(target)) {
                workbook.write(out);
            }
            return List.of(target);
        } catch (IOException e) {
            throw new ConversionException("Could not write spreadsheet " + target + ": " + e.getMessage(), e);
        }
    }

    /** @return number of columns used */
    private static int writePage(Page page, XSSFSheet sheet, Styles styles) {
        int rowIndex = 0;
        int widest = 1;
        boolean first = true;
        for (Block block : page.blocks()) {
            switch (block) {
                case Paragraph p -> {
                    // A sheet-name heading read from Excel is already the tab name.
                    if (first && p.isHeading() && p.text().equals(sheet.getSheetName())) {
                        break;
                    }
                    XSSFCell cell = sheet.createRow(rowIndex++).createCell(0);
                    cell.setCellValue(p.text());
                    if (p.isHeading()) {
                        cell.setCellStyle(styles.bold);
                    }
                }
                case Table t -> {
                    for (int r = 0; r < t.rowCount(); r++) {
                        XSSFRow row = sheet.createRow(rowIndex++);
                        boolean header = t.header() && r == 0;
                        for (int c = 0; c < t.columnCount(); c++) {
                            XSSFCell cell = row.createCell(c);
                            setValue(cell, t.cell(r, c), header);
                            if (header) {
                                cell.setCellStyle(styles.header);
                            }
                        }
                    }
                    if (t.header() && rowIndex == t.rowCount()) {
                        sheet.createFreezePane(0, 1); // keep the header visible while scrolling
                    }
                    widest = Math.max(widest, t.columnCount());
                    rowIndex++; // blank row after a table
                }
                case ImageBlock ignored -> { /* images aren't placed in cells */ }
            }
            first = false;
        }
        return widest;
    }

    private static void setValue(XSSFCell cell, String text, boolean header) {
        String t = text.strip();
        if (!header && NUMBER.matcher(t).matches()) {
            try {
                cell.setCellValue(Double.parseDouble(t.replace(",", "")));
                return;
            } catch (NumberFormatException ignored) {
                // fall through to text
            }
        }
        cell.setCellValue(text);
    }

    private static String sheetName(Page page, int index, Set<String> used) {
        String base = page.blocks().stream()
                .filter(b -> b instanceof Paragraph p && p.isHeading())
                .map(b -> ((Paragraph) b).text())
                .findFirst()
                .map(WorkbookUtil::createSafeSheetName)
                .orElse("Page " + index);
        String name = base;
        for (int n = 2; !used.add(name.toLowerCase(Locale.ROOT)); n++) {
            String suffix = " (" + n + ")";
            name = base.substring(0, Math.min(base.length(), 31 - suffix.length())) + suffix;
        }
        return name;
    }

    private static final class Styles {
        final CellStyle bold;
        final CellStyle header;

        Styles(XSSFWorkbook wb) {
            Font boldFont = wb.createFont();
            boldFont.setBold(true);
            bold = wb.createCellStyle();
            bold.setFont(boldFont);
            header = wb.createCellStyle();
            header.setFont(boldFont);
            header.setFillForegroundColor(IndexedColors.PALE_BLUE.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        }
    }
}

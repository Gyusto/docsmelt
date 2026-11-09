package com.docsmelt.model;

import java.util.ArrayList;
import java.util.List;

/**
 * A grid of text cells, e.g. a spreadsheet range or a Word/PowerPoint table.
 * Rows may have different lengths; missing cells read as "".
 *
 * @param header whether the first row is a header row
 */
public record Table(List<List<String>> rows, boolean header) implements Block {

    public Table {
        List<List<String>> copy = new ArrayList<>(rows.size());
        for (List<String> row : rows) {
            copy.add(row.stream().map(c -> c == null ? "" : c).toList());
        }
        rows = List.copyOf(copy);
    }

    public int rowCount() {
        return rows.size();
    }

    public int columnCount() {
        return rows.stream().mapToInt(List::size).max().orElse(0);
    }

    public String cell(int row, int column) {
        List<String> r = rows.get(row);
        return column < r.size() ? r.get(column) : "";
    }

    public boolean isEmpty() {
        return rows.isEmpty() || columnCount() == 0;
    }
}

package com.docsmelt.io;

import com.docsmelt.model.Table;

import java.util.function.ToDoubleFunction;

/**
 * Works out column widths for a {@link Table}, independent of the drawing
 * library: callers pass a function that measures text in their font.
 *
 * <p>Columns get their natural (widest cell) width when the table fits;
 * otherwise each column keeps at least its longest word and the remaining
 * space is shared in proportion to how much more each column wants.
 */
public final class TableLayout {

    /** Longest cell text measured per column; enough to size columns, keeps huge sheets fast. */
    private static final int SAMPLE_ROWS = 500;
    /** No column is allowed to claim more than this share of the width up front. */
    private static final double MAX_SHARE = 0.6;

    private TableLayout() {
    }

    public static float[] columnWidths(Table table, ToDoubleFunction<String> measure, float available, float padding) {
        int cols = table.columnCount();
        float[] natural = new float[cols];
        float[] minimum = new float[cols];
        int rows = Math.min(table.rowCount(), SAMPLE_ROWS);
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                String text = table.cell(r, c);
                if (text.isEmpty()) {
                    continue;
                }
                natural[c] = Math.max(natural[c], (float) measure.applyAsDouble(text));
                for (String word : text.split("\\s+")) {
                    minimum[c] = Math.max(minimum[c], (float) measure.applyAsDouble(word));
                }
            }
        }
        float cap = (float) (available * MAX_SHARE);
        float naturalSum = 0;
        float minSum = 0;
        for (int c = 0; c < cols; c++) {
            natural[c] = Math.min(natural[c], cap) + 2 * padding + 1;
            minimum[c] = Math.min(Math.min(minimum[c], cap), natural[c]) + 2 * padding + 1;
            minimum[c] = Math.min(minimum[c], natural[c]);
            naturalSum += natural[c];
            minSum += minimum[c];
        }
        if (naturalSum <= available) {
            return natural;
        }
        float[] widths = new float[cols];
        if (minSum >= available) {
            for (int c = 0; c < cols; c++) {
                widths[c] = minimum[c] * available / minSum; // even words must break
            }
            return widths;
        }
        float extra = available - minSum;
        float wanted = naturalSum - minSum;
        for (int c = 0; c < cols; c++) {
            widths[c] = minimum[c] + extra * (natural[c] - minimum[c]) / wanted;
        }
        return widths;
    }
}

package com.sqlteacher.application.exercise;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Side-by-side expected/actual result comparison for a failed (or reveal-always passed)
 * evaluation. Rows keep their natural order; {@code cellDiff} marks, per cell, whether it
 * differs from the paired row on the other side (unpaired rows are fully marked). The
 * diff is computed deterministically in Java; expected rows are only attached when the
 * teacher's reveal mode allows it. Cells may hold {@code null} (SQL NULL).
 */
public record ResultComparison(
    List<String> columns,
    List<ComparisonRow> expectedRows,
    List<ComparisonRow> actualRows
) {
    public ResultComparison {
        columns = List.copyOf(Objects.requireNonNull(columns, "columns must not be null"));
        expectedRows = List.copyOf(Objects.requireNonNull(expectedRows, "expectedRows must not be null"));
        actualRows = List.copyOf(Objects.requireNonNull(actualRows, "actualRows must not be null"));
    }

    public record ComparisonRow(List<Object> cells, List<Boolean> cellDiff) {
        public ComparisonRow {
            // Null-tolerant copy: SQL NULL values are legitimate cell values.
            cells = Collections.unmodifiableList(new ArrayList<>(
                Objects.requireNonNull(cells, "cells must not be null")));
            cellDiff = List.copyOf(Objects.requireNonNull(cellDiff, "cellDiff must not be null"));
            if (cells.size() != cellDiff.size()) {
                throw new IllegalArgumentException("cellDiff must align with cells");
            }
        }
    }
}

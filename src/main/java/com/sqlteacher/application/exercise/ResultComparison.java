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
 *
 * <p>v3.5.0 SFE-1: {@code summary} adds the row/column counts, the first differing cell
 * location and the row-multiset difference so the UI can tell the student 「差一行还是差一列」
 * without eyeballing two tables. It is always computed by Java; {@code rows} stay capped
 * by the comparison row limit and {@code truncated} marks a summary-only degradation.</p>
 */
public record ResultComparison(
    List<String> columns,
    List<ComparisonRow> expectedRows,
    List<ComparisonRow> actualRows,
    ComparisonSummary summary
) {
    public ResultComparison {
        columns = List.copyOf(Objects.requireNonNull(columns, "columns must not be null"));
        expectedRows = List.copyOf(Objects.requireNonNull(expectedRows, "expectedRows must not be null"));
        actualRows = List.copyOf(Objects.requireNonNull(actualRows, "actualRows must not be null"));
    }

    public ResultComparison(List<String> columns, List<ComparisonRow> expectedRows, List<ComparisonRow> actualRows) {
        this(columns, expectedRows, actualRows, null);
    }

    /**
     * 行列计数 + 首个差异定位 + 行多重集合差异（仅期望有/仅实际有的行数）。
     * {@code truncated} 为真表示任一侧超出展示上限，行载荷只是截断视图。
     */
    public record ComparisonSummary(
        int expectedRowCount,
        int actualRowCount,
        boolean columnCountDiffers,
        String firstDiffLocation,
        int expectedOnlyRows,
        int actualOnlyRows,
        boolean truncated
    ) {
        public ComparisonSummary {
            firstDiffLocation = firstDiffLocation == null ? "" : firstDiffLocation.trim();
        }
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

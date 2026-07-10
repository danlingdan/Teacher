package com.sqlteacher.desktop.viewmodel;

import java.util.List;

/**
 * A single result row for the SQL practice page result table.
 *
 * <p>Values are pre-rendered to display strings aligned to the column order of the owning
 * {@link SqlExecutionViewModel}. The ViewModel therefore never holds the raw
 * {@code Map<String, Object>} produced by the backend execution result.
 */
public record SqlResultRowViewModel(
    List<String> cells
) {
    public SqlResultRowViewModel {
        cells = cells == null ? List.of() : List.copyOf(cells);
    }
}

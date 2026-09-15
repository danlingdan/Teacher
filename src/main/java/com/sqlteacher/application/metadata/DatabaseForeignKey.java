package com.sqlteacher.application.metadata;

import java.util.List;
import java.util.Objects;

/**
 * v3.5.0 SCH-1: one imported (outgoing) foreign key of a table. {@code columns} and
 * {@code referencedColumns} are index-aligned so composite keys keep their pairing;
 * SQLite provides no constraint name, so none is modeled here.
 */
public record DatabaseForeignKey(
    List<String> columns,
    String referencedTable,
    List<String> referencedColumns
) {
    public DatabaseForeignKey {
        columns = List.copyOf(Objects.requireNonNull(columns, "columns must not be null"));
        referencedTable = Objects.requireNonNull(referencedTable, "referencedTable must not be null");
        referencedColumns = List.copyOf(
            Objects.requireNonNull(referencedColumns, "referencedColumns must not be null"));
        if (columns.size() != referencedColumns.size()) {
            throw new IllegalArgumentException("columns and referencedColumns must align");
        }
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("foreign key must reference at least one column");
        }
    }
}

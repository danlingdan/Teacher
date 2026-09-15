package com.sqlteacher.application.metadata;

import java.util.List;

public record DatabaseTable(
    String name,
    List<DatabaseColumn> columns,
    List<DatabaseIndex> indexes
) {
    public DatabaseTable {
        columns = List.copyOf(columns);
        indexes = List.copyOf(indexes);
    }

    public DatabaseTable(String name, List<DatabaseColumn> columns) {
        this(name, columns, List.of());
    }
}

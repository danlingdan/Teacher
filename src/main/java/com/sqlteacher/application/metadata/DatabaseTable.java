package com.sqlteacher.application.metadata;

import java.util.List;

public record DatabaseTable(
    String name,
    List<DatabaseColumn> columns,
    List<DatabaseIndex> indexes,
    List<DatabaseForeignKey> foreignKeys
) {
    public DatabaseTable {
        columns = List.copyOf(columns);
        indexes = List.copyOf(indexes);
        foreignKeys = List.copyOf(foreignKeys);
    }

    public DatabaseTable(String name, List<DatabaseColumn> columns) {
        this(name, columns, List.of(), List.of());
    }

    public DatabaseTable(String name, List<DatabaseColumn> columns, List<DatabaseIndex> indexes) {
        this(name, columns, indexes, List.of());
    }
}

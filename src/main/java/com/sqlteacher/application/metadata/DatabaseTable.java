package com.sqlteacher.application.metadata;

import java.util.List;

public record DatabaseTable(
    String name,
    List<DatabaseColumn> columns
) {
    public DatabaseTable {
        columns = List.copyOf(columns);
    }
}

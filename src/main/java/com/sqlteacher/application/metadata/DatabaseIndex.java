package com.sqlteacher.application.metadata;

import java.util.List;

public record DatabaseIndex(
    String name,
    boolean unique,
    List<String> columns
) {
    public DatabaseIndex {
        columns = List.copyOf(columns);
    }
}

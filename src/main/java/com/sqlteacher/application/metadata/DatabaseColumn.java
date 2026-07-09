package com.sqlteacher.application.metadata;

public record DatabaseColumn(
    String name,
    String typeName,
    boolean nullable,
    boolean primaryKey
) {
}

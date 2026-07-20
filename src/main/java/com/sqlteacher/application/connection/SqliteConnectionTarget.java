package com.sqlteacher.application.connection;

import java.nio.file.Path;
import java.util.Objects;

public record SqliteConnectionTarget(Path databasePath) implements DatabaseConnectionTarget {
    public SqliteConnectionTarget {
        Objects.requireNonNull(databasePath, "databasePath must not be null");
        databasePath = databasePath.normalize();
    }

    @Override
    public DatabaseDialect dialect() {
        return DatabaseDialect.SQLITE;
    }
}

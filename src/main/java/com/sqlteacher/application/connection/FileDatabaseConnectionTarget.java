package com.sqlteacher.application.connection;

import java.nio.file.Path;
import java.util.Objects;

/** File-backed DuckDB or H2 target. SQLite keeps its legacy target for compatibility. */
public record FileDatabaseConnectionTarget(
    DatabaseDialect dialect,
    Path databasePath
) implements DatabaseConnectionTarget {
    public FileDatabaseConnectionTarget {
        Objects.requireNonNull(dialect, "dialect must not be null");
        Objects.requireNonNull(databasePath, "databasePath must not be null");
        if (!dialect.fileBased() || dialect == DatabaseDialect.SQLITE) {
            throw new IllegalArgumentException("File database target requires DuckDB or H2 dialect");
        }
        databasePath = databasePath.normalize();
    }
}

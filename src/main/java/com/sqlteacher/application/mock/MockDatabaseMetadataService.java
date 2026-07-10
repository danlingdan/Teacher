package com.sqlteacher.application.mock;

import com.sqlteacher.application.metadata.DatabaseColumn;
import com.sqlteacher.application.metadata.DatabaseMetadataService;
import com.sqlteacher.application.metadata.DatabaseTable;

import java.util.List;

public final class MockDatabaseMetadataService implements DatabaseMetadataService {
    @Override
    public List<DatabaseTable> listTables(String connectionId) {
        requireConnectionId(connectionId);
        return List.of(new DatabaseTable(
            "student",
            List.of(
                new DatabaseColumn("id", "INTEGER", false, true),
                new DatabaseColumn("name", "TEXT", false, false),
                new DatabaseColumn("score", "INTEGER", false, false)
            )
        ));
    }

    private static void requireConnectionId(String connectionId) {
        if (connectionId == null || connectionId.isBlank()) {
            throw new IllegalArgumentException("connectionId must not be blank");
        }
    }
}

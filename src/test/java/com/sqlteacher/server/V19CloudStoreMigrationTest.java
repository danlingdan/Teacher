package com.sqlteacher.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class V19CloudStoreMigrationTest {
    @TempDir Path directory;

    @Test
    void shouldApplyUnifiedSchemaIdempotentlyAndRejectFutureSchema() throws Exception {
        Path database = directory.resolve("cloud.db");
        new V14CloudStore(database);
        new V19CloudStore(database);
        new V19CloudStore(database);
        // v3.4.0 REF-5: the shared migrator stamps the full contiguous history (v1.10 and
        // v1.11 steps included), not only the v1.4/v1.9 rows.
        assertEquals(CloudSchemaMigrator.latestVersion(), maxVersion(database));

        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement()) {
            statement.executeUpdate("insert into cloud_schema_version(version,description,applied_at) values("
                + (CloudSchemaMigrator.latestVersion() + 1) + ",'future',current_timestamp)");
        }
        assertThrows(SQLException.class, () -> new V19CloudStore(database));
    }

    private int maxVersion(Path database) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement();
             var row = statement.executeQuery("select max(version) from cloud_schema_version")) {
            return row.next() ? row.getInt(1) : 0;
        }
    }
}

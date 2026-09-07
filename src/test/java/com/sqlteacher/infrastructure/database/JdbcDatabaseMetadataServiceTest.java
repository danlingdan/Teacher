package com.sqlteacher.infrastructure.database;

import com.sqlteacher.application.config.DatabaseConfiguration;
import com.sqlteacher.application.metadata.DatabaseTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcDatabaseMetadataServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void shouldListTextPrimaryKeyTablesWithoutSQLiteInternalIndexes() throws Exception {
        // 教材数据集使用 TEXT 主键与复合主键；它们会在 SQLite 中生成
        // sqlite_autoindex_* 内部索引，getColumns 无法处理这些内部对象。
        Path database = tempDir.resolve("text-pk.db");
        SqliteDriver.ensureLoaded();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("create table s(sno text primary key, city text)");
            statement.executeUpdate("create table spj(sno text, pno text, qty integer, primary key (sno, pno))");
            statement.executeUpdate("insert into s values ('S1', '天津')");
            statement.executeUpdate("insert into spj values ('S1', 'P1', 200)");
        }

        JdbcDatabaseMetadataService service = new JdbcDatabaseMetadataService((connectionId, timeout) -> {
            if (!"demo".equals(connectionId)) {
                throw new IllegalArgumentException("Unexpected connectionId: " + connectionId);
            }
            return DriverManager.getConnection("jdbc:sqlite:" + database);
        });

        List<DatabaseTable> tables = service.listTables("demo");

        assertEquals(List.of("s", "spj"), tables.stream().map(DatabaseTable::name).sorted().toList());
        assertEquals(List.of("sno", "city"), tables.stream()
            .filter(table -> table.name().equals("s"))
            .findFirst().orElseThrow()
            .columns().stream()
            .map(column -> column.name())
            .toList());
        assertTrue(tables.stream()
            .filter(table -> table.name().equals("spj"))
            .findFirst().orElseThrow()
            .columns().stream().anyMatch(column -> column.primaryKey()));
    }
}

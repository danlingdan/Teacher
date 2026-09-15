package com.sqlteacher.infrastructure.database;

import com.sqlteacher.application.config.DatabaseConfiguration;
import com.sqlteacher.application.metadata.DatabaseForeignKey;
import com.sqlteacher.application.metadata.DatabaseIndex;
import com.sqlteacher.application.metadata.DatabaseTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void shouldListExplicitIndexesWithUniquenessAndOrderedColumns() throws Exception {
        Path database = tempDir.resolve("indexes.db");
        SqliteDriver.ensureLoaded();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("create table student(id integer primary key, email text, city text, name text)");
            statement.executeUpdate("create unique index idx_student_email on student(email)");
            statement.executeUpdate("create index idx_student_city_name on student(city, name)");
        }

        JdbcDatabaseMetadataService service = new JdbcDatabaseMetadataService((connectionId, timeout) -> {
            if (!"demo".equals(connectionId)) {
                throw new IllegalArgumentException("Unexpected connectionId: " + connectionId);
            }
            return DriverManager.getConnection("jdbc:sqlite:" + database);
        });

        DatabaseTable table = service.listTables("demo").stream()
            .filter(candidate -> candidate.name().equals("student"))
            .findFirst().orElseThrow();

        assertEquals(2, table.indexes().size());
        DatabaseIndex emailIndex = table.indexes().stream()
            .filter(index -> index.name().equals("idx_student_email"))
            .findFirst().orElseThrow();
        assertTrue(emailIndex.unique());
        assertEquals(List.of("email"), emailIndex.columns());
        DatabaseIndex compositeIndex = table.indexes().stream()
            .filter(index -> index.name().equals("idx_student_city_name"))
            .findFirst().orElseThrow();
        assertFalse(compositeIndex.unique());
        assertEquals(List.of("city", "name"), compositeIndex.columns());
    }

    /** v3.5.0 SCH-1：演示库形态的外键（自引用 + 复合）都能按约束聚合读出。 */
    @Test
    void shouldReadForeignKeysGroupedByConstraint() throws Exception {
        Path database = tempDir.resolve("foreign-keys.db");
        SqliteDriver.ensureLoaded();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement()) {
            // 自引用（Course.Cpno → Course.Cno）+ 复合外键（ENROLL(Sno, Cno)）各一。
            statement.executeUpdate("create table student(sno text primary key, sname text)");
            statement.executeUpdate("create table progress(sno text, cno text, primary key (sno, cno))");
            statement.executeUpdate("""
                create table course(
                    cno text primary key,
                    cname text,
                    cpno text,
                    foreign key (cpno) references course(cno)
                )""");
            statement.executeUpdate("""
                create table enroll(
                    sno text,
                    cno text,
                    grade integer,
                    primary key (sno, cno),
                    foreign key (sno, cno) references progress(sno, cno)
                )""");
        }

        JdbcDatabaseMetadataService service = new JdbcDatabaseMetadataService((connectionId, timeout) -> {
            if (!"demo".equals(connectionId)) {
                throw new IllegalArgumentException("Unexpected connectionId: " + connectionId);
            }
            return DriverManager.getConnection("jdbc:sqlite:" + database);
        });

        List<DatabaseTable> tables = service.listTables("demo");
        DatabaseForeignKey selfReference = tables.stream()
            .filter(table -> table.name().equals("course"))
            .findFirst().orElseThrow()
            .foreignKeys().stream().findFirst().orElseThrow();
        assertEquals(List.of("cpno"), selfReference.columns());
        assertEquals("course", selfReference.referencedTable());
        assertEquals(List.of("cno"), selfReference.referencedColumns());

        // 复合外键：列与引用列必须按位对齐且保持 KEY_SEQ 顺序。
        DatabaseForeignKey composite = tables.stream()
            .filter(table -> table.name().equals("enroll"))
            .findFirst().orElseThrow()
            .foreignKeys().getFirst();
        assertEquals(List.of("sno", "cno"), composite.columns());
        assertEquals(List.of("sno", "cno"), composite.referencedColumns());
        // student 表没有外键：字段存在且为空列表（契约总返回）。
        assertEquals(List.of(), tables.stream()
            .filter(table -> table.name().equals("student"))
            .findFirst().orElseThrow()
            .foreignKeys());
    }
}

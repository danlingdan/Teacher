package com.sqlteacher.infrastructure.database;

import com.sqlteacher.application.database.DatabaseInitializationResult;
import com.sqlteacher.application.config.AiConfiguration;
import com.sqlteacher.application.config.DatabaseConfiguration;
import com.sqlteacher.application.config.SqlTeacherConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteConfig;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SqliteAppDatabaseInitializerTest {
    @TempDir
    Path tempDir;

    @Test
    void shouldInitializeAppAndDemoDatabases() throws Exception {
        Path appDb = tempDir.resolve("app.db");
        Path demoDb = tempDir.resolve("demo.db");
        SqlTeacherConfiguration properties = new SqlTeacherConfiguration(
            "SQLTeacher",
            tempDir,
            new DatabaseConfiguration(appDb, demoDb),
            new AiConfiguration(URI.create("http://localhost:11434"), Duration.ofSeconds(1), Duration.ofSeconds(30), "test-model")
        );

        DatabaseInitializationResult result = new SqliteAppDatabaseInitializer(properties).initialize();

        assertTrue(result.appDatabaseCreated());
        assertTrue(result.demoDatabaseCreated());
        assertTrue(Files.exists(appDb));
        assertTrue(Files.exists(demoDb));
        assertEquals(25, readSchemaVersion(appDb));
        assertEquals(30, countExercises(appDb));
        assertEquals(20, countExercisesWithThreeHints(appDb));
        assertEquals(7, countDemoRows(demoDb, "Student"));
        assertEquals(8, countDemoRows(demoDb, "Course"));
        assertEquals(11, countDemoRows(demoDb, "SC"));
        assertEquals(5, countDemoRows(demoDb, "S"));
        assertEquals(6, countDemoRows(demoDb, "P"));
        assertEquals(7, countDemoRows(demoDb, "J"));
        assertEquals(19, countDemoRows(demoDb, "SPJ"));

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + appDb);
             var statement = connection.prepareStatement(
                 "update exercises set hints_json = ?, version = 1 where id = 'query-01'"
             )) {
            statement.setString(1, new ExercisePackageCodec().encodeHints(
                java.util.List.of("先写 SELECT 和 FROM。", "使用 ORDER BY id。")
            ));
            statement.executeUpdate();
        }
        new SqliteAppDatabaseInitializer(properties).initialize();
        assertEquals(20, countExercisesWithThreeHints(appDb));
    }

    @Test
    void shouldUpgradeLegacySeededCatalogToBundledBank() throws Exception {
        Path appDb = tempDir.resolve("upgrade-app.db");
        SqlTeacherConfiguration properties = new SqlTeacherConfiguration(
            "SQLTeacher",
            tempDir,
            new DatabaseConfiguration(appDb, tempDir.resolve("upgrade-demo.db")),
            new AiConfiguration(URI.create("http://localhost:11434"), Duration.ofSeconds(1), Duration.ofSeconds(30), "test-model")
        );
        new SqliteAppDatabaseInitializer(properties).initialize();
        // 模拟 v3.1 之前的存量库：旧数据集 school-core-v1，题目引用旧数据集且版本为 2；
        // query-02 被教师改过内容并停用（版本已到 3，与内置包相同）。
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + appDb);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                "insert into exercise_datasets(id, name, setup_sql, version, created_at, updated_at) values "
                    + "('school-core-v1', '学校核心数据集', 'create table student(id integer);', 1, "
                    + "'2026-07-21T00:00:00Z', '2026-07-21T00:00:00Z')"
            );
            statement.executeUpdate(
                "update exercises set dataset_id = 'school-core-v1', version = 2"
            );
            statement.executeUpdate(
                "update exercises set version = 3, enabled = 0, title = '教师改过的题' where id = 'query-02'"
            );
        }

        new SqliteAppDatabaseInitializer(properties).initialize();

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + appDb);
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                 "select id, version, dataset_id, enabled from exercises"
             )) {
            int total = 0;
            int onV2Dataset = 0;
            while (resultSet.next()) {
                total++;
                if ("school-core-v2".equals(resultSet.getString("dataset_id"))) {
                    onV2Dataset++;
                }
                if ("query-02".equals(resultSet.getString("id"))) {
                    assertEquals(3, resultSet.getInt("version"));
                    assertFalse(resultSet.getBoolean("enabled"));
                }
            }
            assertEquals(30, total);
            // query-02 保留教师修改，其余 19 题升级到新数据集；另含 10 道 SPJ 内置题。
            assertEquals(19, onV2Dataset);
        }
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + appDb);
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                 "select count(*) from exercise_datasets where id = 'school-core-v1'"
             )) {
            assertTrue(resultSet.next());
            assertEquals(1, resultSet.getInt(1));
        }
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + appDb);
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                 "select title from exercises where id = 'filter-03'"
             )) {
            assertTrue(resultSet.next());
            assertEquals("查询 B 班学生", resultSet.getString(1));
        }
    }

    @Test
    void shouldRecoverStaleExerciseSessionsAfterInterruptedExit() throws Exception {
        Path appDb = tempDir.resolve("recovery-app.db");
        Path demoDb = tempDir.resolve("recovery-demo.db");
        SqlTeacherConfiguration configuration = new SqlTeacherConfiguration(
            "SQLTeacher",
            tempDir,
            new DatabaseConfiguration(appDb, demoDb),
            new AiConfiguration(
                URI.create("http://localhost:11434"), Duration.ofSeconds(1), Duration.ofSeconds(30), "test-model"
            )
        );
        SqliteAppDatabaseInitializer initializer = new SqliteAppDatabaseInitializer(configuration);
        initializer.initialize();
        String sessionId = "11111111-1111-4111-8111-111111111111";
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + appDb);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                insert into exercise_sessions(id, exercise_id, exercise_version, started_at, hints_used)
                values ('11111111-1111-4111-8111-111111111111', 'query-01', 1, '2026-07-21T00:00:00Z', 0)
                """);
        }
        Path staleFile = tempDir.resolve("exercise-sessions").resolve(sessionId + ".db");
        Files.createDirectories(staleFile.getParent());
        Files.writeString(staleFile, "stale");

        initializer.initialize();

        assertFalse(Files.exists(staleFile));
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + appDb);
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                 "select completed_at from exercise_sessions where id = '" + sessionId + "'"
             )) {
            assertTrue(result.next());
            assertTrue(result.getString(1) != null);
        }
    }

    @Test
    void shouldEnforceReferentialIntegrityOnDemoDatabase() throws Exception {
        Path demoDb = tempDir.resolve("fk-demo.db");
        SqlTeacherConfiguration properties = new SqlTeacherConfiguration(
            "SQLTeacher",
            tempDir,
            new DatabaseConfiguration(tempDir.resolve("fk-app.db"), demoDb),
            new AiConfiguration(URI.create("http://localhost:11434"), Duration.ofSeconds(1), Duration.ofSeconds(30), "test-model")
        );
        new SqliteAppDatabaseInitializer(properties).initialize();

        // v3.4.1 DB-5：演示库参照完整性——Course 自引用、SC 引 Student/Course、SPJ 引 S/P/J。
        assertEquals(1, countForeignKeys(demoDb, "Course"));
        assertEquals(2, countForeignKeys(demoDb, "SC"));
        assertEquals(3, countForeignKeys(demoDb, "SPJ"));

        // 违规写入在开启外键的连接上必须真实失败；越界分数同样被 CHECK 拦截。
        SqliteDriver.ensureLoaded();
        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + demoDb, config.toProperties());
             Statement statement = connection.createStatement()) {
            try (ResultSet pragma = statement.executeQuery("pragma foreign_keys")) {
                assertTrue(pragma.next());
                assertEquals(1, pragma.getInt(1));
            }
            assertThrows(SQLException.class,
                () -> statement.executeUpdate("INSERT INTO SPJ VALUES ('S9', 'P1', 'J1', 1)"));
            assertThrows(SQLException.class,
                () -> statement.executeUpdate("INSERT INTO SC VALUES (99999999, 81001, 50, 20241, 'x')"));
            assertThrows(SQLException.class,
                () -> statement.executeUpdate("INSERT INTO SC VALUES (20180001, 81001, 150, 20241, 'x')"));
            // 合法写入仍然成功：种子数据与合法外键行不受影响。
            assertEquals(1, statement.executeUpdate("INSERT INTO SPJ VALUES ('S1', 'P6', 'J7', 10)"));
        }
    }

    private static int countExercises(Path appDb) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + appDb);
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("select count(*) from exercises")) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private static int countExercisesWithThreeHints(Path appDb) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + appDb);
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                 "select count(*) from exercises where json_array_length(hints_json) = 3 and version = 3"
             )) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private static int readSchemaVersion(Path appDb) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + appDb);
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("select max(version) from schema_version")) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private static int countDemoRows(Path demoDb, String table) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + demoDb);
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private static int countForeignKeys(Path demoDb, String table) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + demoDb);
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("PRAGMA foreign_key_list(" + table + ")")) {
            int count = 0;
            while (resultSet.next()) {
                count++;
            }
            return count;
        }
    }
}

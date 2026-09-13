package com.sqlteacher.server;

import com.sqlteacher.application.collaboration.AuthenticatedUser;
import com.sqlteacher.application.collaboration.UserRole;
import com.sqlteacher.domain.SqlTeacherException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V31ExerciseBankStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void shouldPublishPackageAndExposeManifestAndBlocks() {
        V31ExerciseBankStore store = store();
        AuthenticatedUser admin = admin();

        int first = store.publish(admin, validPackage("查询全部行", "select id, name from t order by id", 1));
        int second = store.publish(admin, validPackage("查询全部行（修订）", "select id, name from t order by id", 2));

        assertEquals(1, first);
        assertEquals(2, second);
        Map<String, Object> manifest = store.manifest();
        assertEquals(2, manifest.get("bankVersion"));
        assertEquals(1, ((java.util.List<?>) manifest.get("datasets")).size());
        assertEquals(1, ((java.util.List<?>) manifest.get("exercises")).size());

        Map<String, Object> block = store.block("EXERCISE", "srv-ex");
        assertNotNull(block);
        assertEquals(2, block.get("version"));
        String content = (String) block.get("content");
        assertTrue(content.contains("查询全部行（修订）"));
        assertTrue(content.contains("===[EXERCISE]==="));
        assertNull(store.block("EXERCISE", "missing"));
    }

    @Test
    void shouldRejectInvalidPackagesWithoutChangingVersion() {
        V31ExerciseBankStore store = store();
        store.publish(admin(), validPackage("初始题", "select id, name from t order by id", 1));

        SqlTeacherException brokenSql = assertThrows(SqlTeacherException.class,
            () -> store.publish(admin(), validPackage("引用缺失表的题", "select name from missing_table", 1)));
        assertEquals("EXERCISE_BANK_INVALID", brokenSql.errorCode());

        SqlTeacherException unparseable = assertThrows(SqlTeacherException.class,
            () -> store.publish(admin(), "this is not a package"));
        assertEquals("EXERCISE_BANK_INVALID", unparseable.errorCode());

        assertEquals(1, store.manifest().get("bankVersion"));
        String content = (String) store.block("EXERCISE", "srv-ex").get("content");
        assertTrue(content.contains("初始题"));
    }

    @Test
    void shouldRejectNonAdminPublisher() {
        V31ExerciseBankStore store = store();
        AuthenticatedUser student = new AuthenticatedUser("u1", "s@example.com", "学生", Set.of(UserRole.STUDENT));

        assertThrows(SecurityException.class, () -> store.publish(student, validPackage("越权发布", "select 1", 1)));
        assertEquals(0, store.manifest().get("bankVersion"));
    }

    @Test
    void shouldMigrateLegacySingleChannelDatabaseIntoNetworkChannel() throws Exception {
        Path db = tempDir.resolve("legacy.db");
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + db);
             var statement = connection.createStatement()) {
            statement.executeUpdate("""
                create table exercise_bank_blocks (
                    block_type text not null, block_id text not null, version integer not null,
                    content text not null, sha256 text not null, bank_version integer not null,
                    primary key(block_type, block_id))
                """);
            statement.executeUpdate("""
                create table exercise_bank_state (
                    id integer primary key check (id = 1), bank_version integer not null, updated_at text not null)
                """);
            statement.executeUpdate(
                "insert into exercise_bank_blocks values ('EXERCISE','legacy-ex',3,'legacy content','sha-legacy',19)");
            statement.executeUpdate("insert into exercise_bank_state values (1,19,'2026-09-01T00:00:00Z')");
        }

        V31ExerciseBankStore store = new V31ExerciseBankStore(db);

        assertEquals(19, store.manifest().get("bankVersion"));
        Map<String, Object> block = store.block("EXERCISE", "legacy-ex");
        assertNotNull(block);
        assertEquals("legacy content", block.get("content"));
        assertEquals(0, v1TableCount(db));
    }

    @Test
    void shouldResumeMigrationFromParkedResidueAfterCrash() throws Exception {
        Path db = tempDir.resolve("residue.db");
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + db);
             var statement = connection.createStatement()) {
            // Simulates the crashed-migration state from the 2026-09-12 production incident:
            // legacy tables were renamed to *_v1 but the backfill never ran, and the
            // current-schema tables do not exist yet.
            statement.executeUpdate("""
                create table exercise_bank_blocks_v1 (
                    block_type text not null, block_id text not null, version integer not null,
                    content text not null, sha256 text not null, bank_version integer not null,
                    primary key(block_type, block_id))
                """);
            statement.executeUpdate("""
                create table exercise_bank_state_v1 (
                    id integer primary key check (id = 1), bank_version integer not null, updated_at text not null)
                """);
            statement.executeUpdate(
                "insert into exercise_bank_blocks_v1 values ('DATASET','legacy-ds',1,'dataset content','sha-ds',19)");
            statement.executeUpdate("insert into exercise_bank_state_v1 values (1,19,'2026-09-01T00:00:00Z')");
            statement.executeUpdate("pragma user_version = 1");
        }

        V31ExerciseBankStore store = new V31ExerciseBankStore(db);

        assertEquals(19, store.manifest().get("bankVersion"));
        assertNotNull(store.block("DATASET", "legacy-ds"));
        assertEquals(0, v1TableCount(db));
    }

    @Test
    void shouldNotResurrectObservationResidueWhenSchemaAlreadyCurrent() throws Exception {
        V31ExerciseBankStore store = store();
        store.publish(admin(), validPackage("现网数据", "select id, name from t order by id", 1));
        Path db = tempDir.resolve("cloud.db");
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + db);
             var statement = connection.createStatement()) {
            // Post-incident production state: *_v1 copies kept for observation while the
            // stamped database already runs the channel schema — a reopen must not
            // backfill stale blocks into the live channel.
            statement.executeUpdate("""
                create table exercise_bank_blocks_v1 (
                    block_type text not null, block_id text not null, version integer not null,
                    content text not null, sha256 text not null, bank_version integer not null,
                    primary key(block_type, block_id))
                """);
            statement.executeUpdate(
                "insert into exercise_bank_blocks_v1 values ('EXERCISE','stale-ex',1,'stale content','sha-stale',19)");
        }

        V31ExerciseBankStore reopened = new V31ExerciseBankStore(db);

        assertEquals(1, reopened.manifest().get("bankVersion"));
        assertNull(reopened.block("EXERCISE", "stale-ex"));
        assertEquals(1, v1TableCount(db));
    }

    private int v1TableCount(Path db) throws Exception {
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + db);
             var statement = connection.createStatement();
             var rows = statement.executeQuery(
                 "select count(*) from sqlite_master where type = 'table' and name like '%_v1'")) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private V31ExerciseBankStore store() {
        try {
            return new V31ExerciseBankStore(tempDir.resolve("cloud.db"));
        } catch (java.sql.SQLException error) {
            throw new IllegalStateException(error);
        }
    }

    @Test
    void shouldKeepChannelsIsolatedAndListThem() {
        V31ExerciseBankStore store = store();
        AuthenticatedUser admin = admin();

        store.publish(admin, validPackage("主频道题", "select id, name from t order by id", 1));
        store.publish(admin, "spj", validPackage("SPJ 频道题", "select id, name from t order by id", 1));

        assertEquals(1, store.manifest().get("bankVersion"));
        Map<String, Object> spjManifest = store.manifest("spj");
        assertEquals(1, spjManifest.get("bankVersion"));
        assertEquals(1, ((java.util.List<?>) spjManifest.get("exercises")).size());

        // 同名分块在两个频道各自维护，内容互不覆盖。
        assertTrue(((String) store.block("network", "EXERCISE", "srv-ex").get("content")).contains("主频道题"));
        assertTrue(((String) store.block("spj", "EXERCISE", "srv-ex").get("content")).contains("SPJ 频道题"));

        var channels = store.channels();
        assertEquals(2, channels.size());
        assertEquals("network", channels.get(0).get("channel"));
        assertEquals("spj", channels.get(1).get("channel"));
    }

    @Test
    void shouldRollBackToHistoricalSnapshotForAllClients() {
        V31ExerciseBankStore store = store();
        AuthenticatedUser admin = admin();
        store.publish(admin, validPackage("第一版题", "select id, name from t order by id", 1));
        store.publish(admin, validPackage("第二版题", "select id, name from t order by id", 2));
        assertEquals(2, store.manifest().get("bankVersion"));

        int rolledBack = store.rollback(admin, "network", 1);

        assertEquals(1, rolledBack);
        assertEquals(1, store.manifest().get("bankVersion"));
        String content = (String) store.block("EXERCISE", "srv-ex").get("content");
        assertTrue(content.contains("第一版题"), content);
        // 未保留的版本拒绝回滚。
        assertThrows(SqlTeacherException.class, () -> store.rollback(admin, "network", 9));
    }

    @Test
    void shouldAuditPublishAndRollback() throws Exception {
        V31ExerciseBankStore store = store();
        AuthenticatedUser admin = admin();
        store.publish(admin, validPackage("审计题", "select id, name from t order by id", 1));
        store.rollback(admin, "network", 1);

        try (var connection = java.sql.DriverManager.getConnection(
                 "jdbc:sqlite:" + tempDir.resolve("cloud.db").toAbsolutePath());
             var statement = connection.createStatement();
             var rows = statement.executeQuery(
                 "select action, count(*) from admin_audit group by action order by action")) {
            java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
            while (rows.next()) {
                counts.put(rows.getString(1), rows.getInt(2));
            }
            assertEquals(1, counts.get("EXERCISE_BANK_PUBLISH"));
            assertEquals(1, counts.get("EXERCISE_BANK_ROLLBACK"));
        }
    }

    private static AuthenticatedUser admin() {
        return new AuthenticatedUser("a1", "admin@example.com", "管理员", Set.of(UserRole.ADMIN));
    }

    private static String validPackage(String title, String referenceSql, int version) {
        return """
            ===[DATASET]===
            ID: srv-ds
            NAME: 服务器数据
            VERSION: 1
            SQL:
            create table t(id integer primary key, name text);
            insert into t values (1, 'a'), (2, 'b');

            ===[EXERCISE]===
            ID: srv-ex
            TITLE: %s
            KNOWLEDGE: 测试
            DIFFICULTY: BEGINNER
            DATASET: srv-ds
            DESCRIPTION:
            返回全部行。
            SQL:
            %s
            COMPARE_COLUMNS: true
            COMPARE_ROWS: true
            ROW_ORDER: true
            VERSION: %d
            ENABLED: true
            """.formatted(title, referenceSql, version);
    }
}

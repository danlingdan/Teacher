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

    private V31ExerciseBankStore store() {
        try {
            return new V31ExerciseBankStore(tempDir.resolve("cloud.db"));
        } catch (java.sql.SQLException error) {
            throw new IllegalStateException(error);
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

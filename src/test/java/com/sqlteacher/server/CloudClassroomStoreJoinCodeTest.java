package com.sqlteacher.server;

import com.sqlteacher.application.collaboration.AuthenticatedUser;
import com.sqlteacher.application.collaboration.UserRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** v3.4.1 CLS-1: classroom join code generation, legacy backfill, and join semantics. */
class CloudClassroomStoreJoinCodeTest {
    private static final String CODE_PATTERN = "[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{8}";

    @TempDir Path directory;

    @Test
    void createGeneratesJoinCodeAndJoinByCodeIsIdempotent() throws Exception {
        Path database = directory.resolve("codes.db");
        CloudClassroomStore store = new CloudClassroomStore(database);
        seedUser(database, "t-1");
        seedUser(database, "s-1");
        AuthenticatedUser teacher = teacher("t-1");
        AuthenticatedUser student = student("s-1");

        var classroom = store.create(teacher, "Join code 101");
        String code = store.joinCode(teacher, classroom.id());
        assertTrue(code.matches(CODE_PATTERN), code);

        store.joinByCode(student, code.toLowerCase(java.util.Locale.ROOT));
        // 重复凭码加入幂等，且不改变既有成员角色（教师重入不得被降级为 STUDENT）。
        store.joinByCode(student, code);
        store.joinByCode(teacher, code);
        var joined = store.joinByCode(student, code);
        assertEquals(2, joined.members().size());
        assertEquals(UserRole.TEACHER,
            joined.members().stream().filter(m -> m.userId().equals("t-1")).findFirst().orElseThrow().role());
        assertEquals(UserRole.STUDENT,
            joined.members().stream().filter(m -> m.userId().equals("s-1")).findFirst().orElseThrow().role());

        String rotated = store.rotateJoinCode(teacher, classroom.id());
        assertNotEquals(code, rotated);
        assertThrows(IllegalArgumentException.class, () -> store.joinByCode(student, code));
        assertEquals(classroom.id(), store.joinByCode(student, rotated).id());
        assertThrows(SecurityException.class, () -> store.rotateJoinCode(student, classroom.id()));
    }

    @Test
    void backfillsJoinCodesForLegacyClassroomsOnStartup() throws Exception {
        Path database = directory.resolve("legacy.db");
        LegacyCloudSchemaInitializer.core(database);
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                "insert into classrooms(id,name,created_at) values('c-legacy','Legacy 101','2026-01-01T00:00:00Z')");
        }

        // Store 构造触发 Migration 9（加列）并回填存量班级短码。
        CloudClassroomStore store = new CloudClassroomStore(database);
        seedUser(database, "s-legacy");

        String backfilled = joinCodeOf(database, "c-legacy");
        assertTrue(backfilled.matches(CODE_PATTERN), backfilled);
        assertEquals("c-legacy", store.joinByCode(student("s-legacy"), backfilled).id());
    }

    /** classroom_members 对 users 有外键：与 HTTP 测试不同，store 级测试需要先落用户行。 */
    private static void seedUser(Path database, String id) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                "insert into users(id,email,display_name,password_hash,password_salt,created_at) values('"
                    + id + "','" + id + "@example.edu','User',x'00',x'00','2026-01-01T00:00:00Z')");
        }
    }

    private static String joinCodeOf(Path database, String classroomId) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                 "select join_code from classrooms where id='" + classroomId + "'")) {
            assertTrue(rows.next());
            return rows.getString(1);
        }
    }

    private static AuthenticatedUser teacher(String id) {
        return new AuthenticatedUser(id, id + "@example.edu", "Teacher", Set.of(UserRole.TEACHER));
    }

    private static AuthenticatedUser student(String id) {
        return new AuthenticatedUser(id, id + "@example.edu", "Student", Set.of(UserRole.STUDENT));
    }
}

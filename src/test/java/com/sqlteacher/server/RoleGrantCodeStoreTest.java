package com.sqlteacher.server;

import com.sqlteacher.application.collaboration.AuthenticatedUser;
import com.sqlteacher.application.collaboration.UserRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoleGrantCodeStoreTest {
    private static final String UNIFORM_INVALID = "role code is invalid or has expired";
    @TempDir Path directory;

    private CloudAdministrationStore newStore() throws Exception {
        return new CloudAdministrationStore(directory.resolve("cloud.db"));
    }

    private AuthenticatedUser user(String id, UserRole... roles) {
        return new AuthenticatedUser(id, id + "@example.com", "User " + id, java.util.Set.of(roles));
    }

    private void insertUser(String id) throws Exception {
        try (var connection = connection(); var statement = connection.prepareStatement(
            "insert into users(id,email,display_name,password_hash,password_salt,disabled,created_at) values(?,?,?,?,0,?,?)")) {
            statement.setString(1, id); statement.setString(2, id + "@example.com"); statement.setString(3, "User");
            statement.setBytes(4, new byte[]{1}); statement.setString(5, Instant.now().toString());
            statement.setString(6, Instant.now().toString());
            statement.executeUpdate();
        }
    }

    @Test void issueReturnsPlaintextOnceAndListingHidesIt() throws Exception {
        CloudAdministrationStore store = newStore();
        insertUser("admin-1");
        var admin = user("admin-1", UserRole.ADMIN);
        var issued = store.issueTeacherRoleCode(admin, 30);
        assertEquals(8, issued.code().length(), "codes must match the documented 8-character format");
        assertTrue(issued.code().matches("[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{8}"),
            "codes must use the unambiguous uppercase alphabet");
        var listed = store.listTeacherRoleCodes(admin);
        assertEquals(1, listed.size());
        assertEquals(issued.codeHash(), listed.get(0).codeHash());
        assertNotEquals(issued.code(), listed.get(0).codeHash(), "the plaintext must never come back in listings");
        assertEquals("TEACHER", listed.get(0).role());
        assertNull(listed.get(0).usedBy());
        assertNull(listed.get(0).revokedAt());
    }

    @Test void nonAdminCannotIssueListOrRevoke() throws Exception {
        CloudAdministrationStore store = newStore();
        insertUser("admin-1");
        insertUser("student-1");
        var admin = user("admin-1", UserRole.ADMIN);
        var student = user("student-1", UserRole.STUDENT);
        var issued = store.issueTeacherRoleCode(admin, 30);
        assertThrows(SecurityException.class, () -> store.issueTeacherRoleCode(student, 30));
        assertThrows(SecurityException.class, () -> store.listTeacherRoleCodes(student));
        assertThrows(SecurityException.class, () -> store.revokeTeacherRoleCode(student, issued.codeHash()));
    }

    @Test void redemptionGrantsTheTeacherRoleExactlyOnceAndIsCaseInsensitive() throws Exception {
        CloudAdministrationStore store = newStore();
        insertUser("admin-1");
        insertUser("student-1");
        var admin = user("admin-1", UserRole.ADMIN);
        var student = user("student-1", UserRole.STUDENT);
        var issued = store.issueTeacherRoleCode(admin, 30);
        store.redeemRoleCode(student, issued.code().toLowerCase());
        assertTrue(userHasRole("student-1", "TEACHER"));
        assertEquals("student-1", store.listTeacherRoleCodes(admin).get(0).usedBy());
        IllegalArgumentException replay = assertThrows(IllegalArgumentException.class,
            () -> store.redeemRoleCode(student, issued.code()), "a consumed code must not redeem twice");
        assertEquals(UNIFORM_INVALID, replay.getMessage());
    }

    @Test void wrongUnknownRevokedAndAlreadyTeacherFailuresStayUniformOrExplicit() throws Exception {
        CloudAdministrationStore store = newStore();
        insertUser("admin-1");
        insertUser("student-1");
        insertUser("teacher-1");
        var admin = user("admin-1", UserRole.ADMIN);
        var student = user("student-1", UserRole.STUDENT);
        var issued = store.issueTeacherRoleCode(admin, 30);
        String wrong = (issued.code().charAt(0) == '2' ? "3" : "2") + issued.code().substring(1);
        assertEquals(UNIFORM_INVALID, assertThrows(IllegalArgumentException.class,
            () -> store.redeemRoleCode(student, wrong)).getMessage());
        assertEquals(UNIFORM_INVALID, assertThrows(IllegalArgumentException.class,
            () -> store.redeemRoleCode(student, "AAAAA222")).getMessage());
        store.revokeTeacherRoleCode(admin, issued.codeHash());
        assertEquals(UNIFORM_INVALID, assertThrows(IllegalArgumentException.class,
            () -> store.redeemRoleCode(student, issued.code())).getMessage(),
            "revoked codes must be indistinguishable from wrong ones");
        // 已是教师的账号拒绝兑换且不消耗码：随后学生仍可用同一码成功。
        var second = store.issueTeacherRoleCode(admin, 30);
        assertEquals("account already has the teacher role", assertThrows(IllegalArgumentException.class,
            () -> store.redeemRoleCode(user("teacher-1", UserRole.TEACHER), second.code())).getMessage());
        assertDoesNotThrow(() -> store.redeemRoleCode(student, second.code()));
    }

    @Test void expiredCodesAreRejectedWithTheUniformMessage() throws Exception {
        CloudAdministrationStore store = newStore();
        insertUser("admin-1");
        insertUser("student-1");
        var issued = store.issueTeacherRoleCode(user("admin-1", UserRole.ADMIN), 30);
        try (var connection = connection(); var statement = connection.prepareStatement(
            "update role_grant_codes set expires_at=?")) {
            statement.setString(1, Instant.now().minusSeconds(60).toString());
            statement.executeUpdate();
        }
        assertEquals(UNIFORM_INVALID, assertThrows(IllegalArgumentException.class,
            () -> store.redeemRoleCode(user("student-1", UserRole.STUDENT), issued.code())).getMessage());
    }

    private java.sql.Connection connection() throws Exception {
        return java.sql.DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("cloud.db").toAbsolutePath());
    }

    private boolean userHasRole(String userId, String role) throws Exception {
        try (var connection = connection(); var statement = connection.prepareStatement(
            "select count(*) from user_roles where user_id=? and role=?")) {
            statement.setString(1, userId);
            statement.setString(2, role);
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getInt(1) == 1;
            }
        }
    }
}

package com.sqlteacher.desktop.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlteacher.application.collaboration.CloudAccountApi;
import com.sqlteacher.application.collaboration.CloudAuthApi;
import com.sqlteacher.application.collaboration.CloudAuthenticationService;
import com.sqlteacher.application.collaboration.UserRole;
import com.sqlteacher.infrastructure.cloud.InMemoryLearningEventOwnerContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.sqlteacher.desktop.bridge.ApiSectionTestSupport.fake;
import static com.sqlteacher.desktop.bridge.ApiSectionTestSupport.hostWithBeans;
import static com.sqlteacher.desktop.bridge.ApiSectionTestSupport.hostWithoutCore;
import static com.sqlteacher.desktop.bridge.ApiSectionTestSupport.session;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v3.4.0 REF-9: the account section wires the cloud auth ports to the in-memory session and
 * the local learning-owner context: sign-in switches the owner, a failing remote logout never
 * blocks the local sign-out, and privileged account calls require an authenticated session.
 */
class AccountApiSectionTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void accountLoginRejectsBlankEmailWithoutTheCore() {
        AccountApiSection section = new AccountApiSection(hostWithoutCore());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> section.handle("account.login", mapper.createObjectNode()
                .put("password", "secret"), () -> false, ignored -> { }));
        assertEquals("email must contain at most 320 characters", error.getMessage());
    }

    @Test
    void accountLoginSignsInTheSessionAndSwitchesTheLearningOwner() throws Exception {
        List<String> emails = new ArrayList<>();
        List<String> passwords = new ArrayList<>();
        CloudAuthenticationService.Session teacher = session("u-1", "教师账号", UserRole.TEACHER);
        CloudAuthApi auth = fake(CloudAuthApi.class, Map.of(
            "login", args -> {
                emails.add((String) args[0]);
                passwords.add(new String((char[]) args[1]));
                return teacher;
            }));
        var sessions = new ApiSectionTestSupport.FakeCloudSessions();
        var owners = new InMemoryLearningEventOwnerContext();
        try (var host = hostWithBeans(auth, sessions, owners)) {
            AccountApiSection section = new AccountApiSection(host);

            JsonNode result = section.handle("account.login", mapper.createObjectNode()
                .put("email", "  t@sqlteacher.test  ")
                .put("password", "secret"), () -> false, ignored -> { });

            assertEquals("u-1", result.path("subjectId").asText());
            assertEquals("教师账号", result.path("displayName").asText());
            assertEquals("TEACHER", result.path("role").asText());
            assertEquals("教师", result.path("roleLabel").asText());
            assertTrue(result.path("authenticated").asBoolean());
        }
        assertEquals(List.of("t@sqlteacher.test"), emails);
        assertEquals(List.of("secret"), passwords);
        assertEquals(1, sessions.signInCount);
        assertTrue(sessions.current().isPresent());
        assertEquals("u-1", owners.currentOwnerId());
    }

    @Test
    void accountLogoutKeepsTheLocalSignOutWhenTheRemoteLogoutFails() throws Exception {
        CloudAuthApi auth = fake(CloudAuthApi.class, Map.of(
            "logout", args -> {
                throw new IllegalStateException("cloud unreachable");
            }));
        var sessions = new ApiSectionTestSupport.FakeCloudSessions();
        sessions.signIn(session("u-1", "教师账号", UserRole.TEACHER));
        var owners = new InMemoryLearningEventOwnerContext();
        owners.useAuthenticatedUser("u-1");
        try (var host = hostWithBeans(auth, sessions, owners)) {
            AccountApiSection section = new AccountApiSection(host);

            JsonNode result = section.handle("account.logout", mapper.createObjectNode(), () -> false, ignored -> { });

            assertFalse(result.path("remoteLogoutSucceeded").asBoolean());
            assertEquals("guest", result.path("subjectId").asText());
            assertFalse(result.path("authenticated").asBoolean());
        }
        assertEquals(1, sessions.signOutCount);
        assertTrue(sessions.current().isEmpty());
        assertEquals("guest", owners.currentOwnerId());
    }

    @Test
    void accountPasswordChangeRequiresAnAuthenticatedSession() {
        try (var host = hostWithBeans(new ApiSectionTestSupport.FakeCloudSessions())) {
            AccountApiSection section = new AccountApiSection(host);

            SecurityException error = assertThrows(SecurityException.class,
                () -> section.handle("account.password.change", mapper.createObjectNode()
                    .put("currentPassword", "old-secret")
                    .put("newPassword", "new-secret"), () -> false, ignored -> { }));
            assertEquals("An authenticated cloud session is required", error.getMessage());
        }
    }

    @Test
    void accountSessionRevokeMapsTheSessionIdAndAccessToken() throws Exception {
        List<Object[]> revocations = new ArrayList<>();
        CloudAccountApi account = fake(CloudAccountApi.class, Map.of(
            "revokeSession", args -> {
                revocations.add(args);
                return null;
            }));
        var sessions = new ApiSectionTestSupport.FakeCloudSessions();
        var teacher = session("u-1", "教师账号", UserRole.TEACHER);
        sessions.signIn(teacher);
        try (var host = hostWithBeans(account, sessions)) {
            AccountApiSection section = new AccountApiSection(host);

            JsonNode result = section.handle("account.session.revoke", mapper.createObjectNode()
                .put("sessionId", "sess-9"), () -> false, ignored -> { });

            assertTrue(result.path("revoked").asBoolean());
            assertEquals("sess-9", result.path("sessionId").asText());
        }
        assertEquals(1, revocations.size());
        assertEquals(teacher.accessToken(), revocations.get(0)[0]);
        assertEquals("sess-9", revocations.get(0)[1]);
    }
}

package com.sqlteacher.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlTeacherCloudServerTest {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    @TempDir Path directory;
    private SqlTeacherCloudServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop();
    }

    @Test
    void shouldRotateRefreshTokensAndRejectReplay() throws Exception {
        start();
        JsonNode registered = post("auth/register", null, """
            {"email":"student@example.edu","displayName":"Student","password":"strong-password-123"}
            """);

        JsonNode refreshed = post("auth/refresh", null,
            JSON.writeValueAsString(java.util.Map.of("refreshToken", registered.get("refreshToken").asText())));

        assertNotEquals(registered.get("accessToken").asText(), refreshed.get("accessToken").asText());
        assertNotEquals(registered.get("refreshToken").asText(), refreshed.get("refreshToken").asText());
        assertEquals(401, postStatus("auth/refresh", null,
            JSON.writeValueAsString(java.util.Map.of("refreshToken", registered.get("refreshToken").asText()))));
    }

    @Test
    void shouldEnforceAssignmentLifecycleAndExportClassScopedCsv() throws Exception {
        Path database = start();
        JsonNode teacher = register("teacher@example.edu", "Teacher");
        JsonNode student = register("student@example.edu", "Student");
        promoteTeacher(database, teacher.at("/user/id").asText());
        String teacherToken = teacher.get("accessToken").asText();

        JsonNode classroom = post("classes", teacherToken, "{\"name\":\"Database 101\"}");
        String classroomId = classroom.get("id").asText();
        post("classes/" + classroomId + "/members", teacherToken,
            JSON.writeValueAsString(java.util.Map.of("email", "student@example.edu", "role", "STUDENT")));
        JsonNode assignment = post("classes/" + classroomId + "/assignments", teacherToken,
            JSON.writeValueAsString(java.util.Map.of("exerciseId", "select-1", "title", "First task",
                "dueAt", Instant.now().plusSeconds(3600).toString())));

        JsonNode closed = post("classes/" + classroomId + "/assignments/" + assignment.get("id").asText()
            + "/status", teacherToken, JSON.writeValueAsString(java.util.Map.of(
                "status", "CLOSED", "expectedVersion", assignment.get("version").asLong())));
        JsonNode archived = post("classes/" + classroomId + "/assignments/" + assignment.get("id").asText()
            + "/status", teacherToken, JSON.writeValueAsString(java.util.Map.of(
                "status", "ARCHIVED", "expectedVersion", closed.get("version").asLong())));

        assertEquals("CLOSED", closed.get("status").asText());
        assertEquals("ARCHIVED", archived.get("status").asText());
        assertEquals(403, getStatus("classes/" + classroomId + "/analytics", student.get("accessToken").asText()));
        String csv = getText("classes/" + classroomId + "/analytics/export", teacherToken);
        assertTrue(csv.startsWith("\uFEFFstudent_email,event_type,occurred_at,successful"));
    }

    @Test
    void shouldCreateCopyFilterAndProtectVersionedAssignmentDrafts() throws Exception {
        Path database = start();
        JsonNode teacher = register("draft-teacher@example.edu", "Draft Teacher");
        JsonNode student = register("draft-student@example.edu", "Draft Student");
        promoteTeacher(database, teacher.at("/user/id").asText());
        String teacherToken = teacher.get("accessToken").asText();
        String studentToken = student.get("accessToken").asText();
        String classroomId = post("classes", teacherToken, "{\"name\":\"Versioned tasks\"}")
            .get("id").asText();
        post("classes/" + classroomId + "/members", teacherToken,
            JSON.writeValueAsString(java.util.Map.of(
                "email", "draft-student@example.edu", "role", "STUDENT")));

        JsonNode draft = post("classes/" + classroomId + "/assignments", teacherToken,
            JSON.writeValueAsString(java.util.Map.of(
                "exerciseId", "select-versioned", "title", "Draft task", "description", "Initial notes",
                "status", "DRAFT", "dueAt", Instant.now().plusSeconds(7_200).toString())));

        assertEquals("DRAFT", draft.get("status").asText());
        assertEquals(1, draft.get("version").asLong());
        assertTrue(draft.get("publishedAt").isNull());
        assertEquals(0, JSON.readTree(getText("classes/" + classroomId + "/assignments", studentToken))
            .get("assignments").size());

        String assignmentPath = "classes/" + classroomId + "/assignments/" + draft.get("id").asText();
        JsonNode updated = post(assignmentPath + "/details", teacherToken,
            JSON.writeValueAsString(java.util.Map.of(
                "title", "Updated draft", "description", "Updated notes",
                "dueAt", Instant.now().plusSeconds(10_800).toString(), "expectedVersion", 1)));
        assertEquals(2, updated.get("version").asLong());
        assertEquals("Updated notes", updated.get("description").asText());

        HttpResponse<String> conflict = send("POST", assignmentPath + "/details", teacherToken,
            JSON.writeValueAsString(java.util.Map.of(
                "title", "Stale update", "description", "Must not win",
                "dueAt", Instant.now().plusSeconds(10_800).toString(), "expectedVersion", 1)));
        assertEquals(409, conflict.statusCode());
        JsonNode conflictBody = JSON.readTree(conflict.body());
        assertEquals("ASSIGNMENT_VERSION_CONFLICT", conflictBody.get("code").asText());
        assertEquals(2, conflictBody.at("/latest/version").asLong());

        JsonNode copy = post(assignmentPath + "/copy", teacherToken, "{\"expectedVersion\":2}");
        assertEquals("DRAFT", copy.get("status").asText());
        assertEquals(draft.get("id").asText(), copy.get("copiedFromAssignmentId").asText());
        assertEquals(1, copy.get("version").asLong());

        JsonNode published = post("classes/" + classroomId + "/assignments/" + copy.get("id").asText()
            + "/status", teacherToken, "{\"status\":\"PUBLISHED\",\"expectedVersion\":1}");
        assertEquals("PUBLISHED", published.get("status").asText());
        assertEquals(2, published.get("version").asLong());
        assertTrue(!published.get("publishedAt").isNull());

        JsonNode drafts = JSON.readTree(getText(
            "classes/" + classroomId + "/assignments?status=DRAFT", teacherToken));
        assertEquals(1, drafts.get("assignments").size());
    }

    @Test
    void shouldUpgradeLegacyAssignmentsWithoutLosingPublishedState() throws Exception {
        Path database = directory.resolve("legacy-cloud.db");
        String createdAt = "2026-07-22T00:00:00Z";
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement()) {
            statement.executeUpdate("create table class_assignments(id text primary key,classroom_id text not null,"
                + "exercise_id text not null,title text not null,created_at text not null,status text not null,"
                + "due_at text,updated_at text not null)");
            statement.executeUpdate("insert into class_assignments(id,classroom_id,exercise_id,title,created_at,"
                + "status,due_at,updated_at) values('legacy-task','legacy-class','select-1','Legacy task','"
                + createdAt + "','PUBLISHED',null,'" + createdAt + "')");
        }

        server = new SqlTeacherCloudServer(database, 0);
        server.start();

        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.prepareStatement(
                 "select description,published_at,copied_from_assignment_id,version from class_assignments where id='legacy-task'");
             var row = statement.executeQuery()) {
            assertTrue(row.next());
            assertEquals("", row.getString("description"));
            assertEquals(createdAt, row.getString("published_at"));
            assertEquals(null, row.getString("copied_from_assignment_id"));
            assertEquals(1, row.getLong("version"));
        }
    }

    private Path start() throws Exception {
        Path database = directory.resolve("cloud.db");
        server = new SqlTeacherCloudServer(database, 0);
        server.start();
        return database;
    }

    private JsonNode register(String email, String name) throws Exception {
        return post("auth/register", null, JSON.writeValueAsString(java.util.Map.of(
            "email", email, "displayName", name, "password", "strong-password-123")));
    }

    private void promoteTeacher(Path database, String userId) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.prepareStatement(
                 "insert or ignore into user_roles(user_id,role) values(?, 'TEACHER')")) {
            statement.setString(1, userId);
            statement.executeUpdate();
        }
    }

    private JsonNode post(String path, String token, String body) throws Exception {
        HttpResponse<String> response = send("POST", path, token, body);
        assertTrue(response.statusCode() >= 200 && response.statusCode() < 300, response.body());
        return response.body().isBlank() ? JSON.nullNode() : JSON.readTree(response.body());
    }

    private int postStatus(String path, String token, String body) throws Exception {
        return send("POST", path, token, body).statusCode();
    }

    private int getStatus(String path, String token) throws Exception {
        return send("GET", path, token, null).statusCode();
    }

    private String getText(String path, String token) throws Exception {
        HttpResponse<String> response = send("GET", path, token, null);
        assertEquals(200, response.statusCode(), response.body());
        return response.body();
    }

    private HttpResponse<String> send(String method, String path, String token, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
            URI.create("http://127.0.0.1:" + server.port() + "/api/v1/" + path));
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (body == null) request.method(method, HttpRequest.BodyPublishers.noBody());
        else request.header("Content-Type", "application/json")
            .method(method, HttpRequest.BodyPublishers.ofString(body));
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}

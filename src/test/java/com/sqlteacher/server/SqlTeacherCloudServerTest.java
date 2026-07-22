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
            + "/status", teacherToken, "{\"status\":\"CLOSED\"}");
        JsonNode archived = post("classes/" + classroomId + "/assignments/" + assignment.get("id").asText()
            + "/status", teacherToken, "{\"status\":\"ARCHIVED\"}");

        assertEquals("CLOSED", closed.get("status").asText());
        assertEquals("ARCHIVED", archived.get("status").asText());
        assertEquals(403, getStatus("classes/" + classroomId + "/analytics", student.get("accessToken").asText()));
        String csv = getText("classes/" + classroomId + "/analytics/export", teacherToken);
        assertTrue(csv.startsWith("\uFEFFstudent_email,event_type,occurred_at,successful"));
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

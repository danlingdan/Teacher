package com.sqlteacher.infrastructure.cloud;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpCloudApiClientTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ASSIGNMENT_JSON = """
        {"id":"assignment-1","classroomId":"class-1","exerciseId":"select-1",\
        "title":"查询练习","createdAt":"2026-07-22T00:00:00Z"}
        """;
    private static final String SUBMISSION_JSON = """
        {"id":"submission-1","operationId":"operation-0001","classroomId":"class-1",\
        "assignmentId":"assignment-1","userId":"student-1","attemptNumber":1,"status":"PASSED",\
        "resultHash":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",\
        "submittedAt":"2026-07-28T00:00:00Z"}
        """;
    private static final String ANALYTICS_JSON = """
        {"classroomId":"class-1","assignmentId":"assignment-1","totalStudents":1,\
        "submittedStudents":1,"passedStudents":1,"totalAttempts":1,"completionRate":1.0,"passRate":1.0,\
        "commonErrors":[],"rows":[],"page":0,"pageSize":50,"totalRows":1,\
        "generatedAt":"2026-07-28T00:00:00Z"}
        """;
    private static final String ADMIN_USER_JSON = """
        {"id":"user-1","email":"student@example.com","displayName":"Student",\
        "roles":["STUDENT"],"disabled":true,"createdAt":"2026-07-28T00:00:00Z"}
        """;

    private HttpServer server;
    private HttpCloudApiClient client;
    private String requestMethod;
    private String authorization;
    private JsonNode requestBody;
    private String requestQuery;
    private String requestPath;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/classes/class-1/assignments", this::assignments);
        server.createContext("/api/v1/admin", this::admin);
        server.start();
        client = new HttpCloudApiClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void shouldCreateAssignmentWithAuthenticatedRequest() {
        var assignment = client.createAssignment("access-token", "class-1", "select-1", "查询练习");

        assertEquals("POST", requestMethod);
        assertEquals("Bearer access-token", authorization);
        assertEquals("select-1", requestBody.get("exerciseId").asText());
        assertEquals("查询练习", requestBody.get("title").asText());
        assertEquals("assignment-1", assignment.id());
        assertEquals(Instant.parse("2026-07-22T00:00:00Z"), assignment.createdAt());
    }

    @Test
    void shouldListAssignmentsForClassMember() {
        var assignments = client.listAssignments("member-token", "class-1");

        assertEquals("GET", requestMethod);
        assertEquals("Bearer member-token", authorization);
        assertEquals(1, assignments.size());
        assertEquals("select-1", assignments.getFirst().exerciseId());
    }

    @Test
    void shouldSendDraftAndVersionedAssignmentRequests() {
        client.createAssignmentDraft("teacher-token", "class-1", "select-1", "草稿任务", "课堂说明",
            Instant.parse("2026-08-01T00:00:00Z"));

        assertEquals("DRAFT", requestBody.get("status").asText());
        assertEquals("课堂说明", requestBody.get("description").asText());

        client.changeAssignmentStatus("teacher-token", "class-1", "assignment-1",
            com.sqlteacher.application.collaboration.AssignmentStatus.PUBLISHED, 7);

        assertEquals("PUBLISHED", requestBody.get("status").asText());
        assertEquals(7, requestBody.get("expectedVersion").asLong());
    }

    @Test
    void shouldSendOnlyDeterministicSubmissionSummary() {
        var submission = client.submitAssignment("student-token", "class-1", "assignment-1",
            new com.sqlteacher.application.collaboration.AssignmentSubmissionRequest(
                "operation-0001", true, "a".repeat(64), null, Instant.parse("2026-07-28T00:00:00Z")));

        assertEquals("operation-0001", requestBody.get("operationId").asText());
        assertEquals("a".repeat(64), requestBody.get("resultHash").asText());
        assertEquals(null, requestBody.get("sql"));
        assertEquals(1, submission.attemptNumber());
    }

    @Test
    void shouldEncodeAssignmentAnalyticsFilter() {
        var report = client.getAssignmentAnalytics("teacher-token", "class-1", "assignment-1",
            new com.sqlteacher.application.collaboration.AssignmentAnalyticsFilter(
                com.sqlteacher.application.collaboration.AssignmentStudentStatus.FAILED,
                Instant.parse("2026-07-01T00:00:00Z"), Instant.parse("2026-07-31T00:00:00Z"), 0, 50));

        assertTrue(requestQuery.contains("status=FAILED"));
        assertTrue(requestQuery.contains("from=2026-07-01T00%3A00%3A00Z"));
        assertEquals(1, report.totalRows());
    }

    @Test
    void shouldCallAdministratorOperationsAndEncodeAuditFilter() {
        var health = client.getAdminHealth("admin-token");

        assertEquals(3, health.activeUsers());
        assertEquals("Bearer admin-token", authorization);

        var user = client.setUserDisabled("admin-token", "user-1", true, "POLICY_VIOLATION");

        assertEquals("POST", requestMethod);
        assertEquals("POLICY_VIOLATION", requestBody.get("reasonCode").asText());
        assertTrue(user.disabled());

        var audit = client.getAdminAudit("admin-token", "AUTH_LOGIN", Instant.parse("2026-07-01T00:00:00Z"),
            Instant.parse("2026-07-31T00:00:00Z"), 1, 20);

        assertTrue(requestQuery.contains("action=AUTH_LOGIN"));
        assertTrue(requestQuery.contains("page=1"));
        assertEquals(1, audit.totalRows());
    }

    @Test
    void shouldSendRetentionConfirmationAndBackupReference() {
        var preview = client.previewRetention("admin-token",
            com.sqlteacher.application.collaboration.RetentionCategory.SYNC_EVENTS,
            Instant.parse("2026-07-01T00:00:00Z"));

        assertEquals("SYNC_EVENTS", requestBody.get("category").asText());
        assertEquals(2, preview.affectedRows());

        var completed = client.executeRetention("admin-token", preview.id(), preview.confirmationToken(),
            "cloud-20260728.db");

        assertEquals("cloud-20260728.db", requestBody.get("backupReference").asText());
        assertEquals("COMPLETED", completed.status());

        var restored = client.restoreRetention("admin-token", completed.id());

        assertTrue(requestPath.endsWith("/restore"));
        assertEquals("RESTORED", restored.status());
    }

    @Test
    void shouldAllowHttpsAndLoopbackHttpEndpointsOnly() {
        assertDoesNotThrow(() -> new HttpCloudApiClient(URI.create("https://api.example.edu")));
        assertDoesNotThrow(() -> new HttpCloudApiClient(URI.create("http://localhost:18080")));
        assertThrows(IllegalArgumentException.class,
            () -> new HttpCloudApiClient(URI.create("http://8.130.47.235")));
    }

    private void assignments(HttpExchange exchange) throws IOException {
        requestMethod = exchange.getRequestMethod();
        requestQuery = exchange.getRequestURI().getRawQuery();
        authorization = exchange.getRequestHeaders().getFirst("Authorization");
        byte[] requestBytes = exchange.getRequestBody().readAllBytes();
        requestBody = requestBytes.length == 0 ? null : JSON.readTree(requestBytes);
        boolean submissionRequest = exchange.getRequestURI().getPath().endsWith("/submissions");
        boolean analyticsRequest = exchange.getRequestURI().getPath().endsWith("/analytics");
        String response = analyticsRequest ? ANALYTICS_JSON : submissionRequest
            ? ("GET".equals(requestMethod) ? "{\"submissions\":[" + SUBMISSION_JSON + "]}" : SUBMISSION_JSON)
            : ("GET".equals(requestMethod) ? "{\"assignments\":[" + ASSIGNMENT_JSON + "]}" : ASSIGNMENT_JSON);
        byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(200, responseBytes.length);
        exchange.getResponseBody().write(responseBytes);
        exchange.close();
    }

    private void admin(HttpExchange exchange) throws IOException {
        requestMethod = exchange.getRequestMethod();
        requestPath = exchange.getRequestURI().getPath();
        requestQuery = exchange.getRequestURI().getRawQuery();
        authorization = exchange.getRequestHeaders().getFirst("Authorization");
        byte[] requestBytes = exchange.getRequestBody().readAllBytes();
        requestBody = requestBytes.length == 0 ? null : JSON.readTree(requestBytes);
        String path = exchange.getRequestURI().getPath();
        String response;
        if (path.endsWith("/retention/preview")) {
            response = """
                {"id":"11111111-1111-1111-1111-111111111111","category":"SYNC_EVENTS",
                "cutoff":"2026-07-01T00:00:00Z","affectedRows":2,"expiresAt":"2026-07-28T00:15:00Z",
                "confirmationToken":"confirm-token"}
                """;
        } else if (path.endsWith("/retention/execute")) {
            response = retentionJobJson("COMPLETED", null);
        } else if (path.endsWith("/restore")) {
            response = retentionJobJson("RESTORED", "2026-07-28T00:10:00Z");
        } else if (path.endsWith("/health")) {
            response = """
                {"activeUsers":3,"disabledUsers":1,"activeAccessSessions":2,"activeRefreshSessions":2,
                "assignments":4,"submissions":5,"generatedAt":"2026-07-28T00:00:00Z"}
                """;
        } else if (path.endsWith("/audit")) {
            response = """
                {"entries":[{"id":"audit-1","actorUserId":"admin-1","action":"AUTH_LOGIN",
                "targetType":"USER","targetId":"user-1","result":"SUCCESS","reasonCode":"CREDENTIAL_VERIFIED",
                "correlationId":"request-1","createdAt":"2026-07-28T00:00:00Z"}],
                "page":1,"pageSize":20,"totalRows":1}
                """;
        } else {
            response = ADMIN_USER_JSON;
        }
        byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(200, responseBytes.length);
        exchange.getResponseBody().write(responseBytes);
        exchange.close();
    }

    private String retentionJobJson(String status, String restoredAt) {
        return "{\"id\":\"11111111-1111-1111-1111-111111111111\",\"category\":\"SYNC_EVENTS\","
            + "\"cutoff\":\"2026-07-01T00:00:00Z\",\"previewRows\":2,\"affectedRows\":2,"
            + "\"status\":\"" + status + "\",\"backupReference\":\"cloud-20260728.db\","
            + "\"createdAt\":\"2026-07-28T00:00:00Z\",\"executedAt\":\"2026-07-28T00:05:00Z\","
            + "\"restoredAt\":" + (restoredAt == null ? "null" : "\"" + restoredAt + "\"") + "}";
    }
}

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
    void shouldKeepStrongPasswordCreationPolicyWhileAcceptingProvisionedPasswordAtLogin() throws Exception {
        start();

        assertEquals(400, postStatus("auth/register", null, """
            {"email":"short-password@example.edu","displayName":"Short Password","password":"123456"}
            """));
        assertEquals(401, postStatus("auth/login", null, """
            {"email":"short-password@example.edu","password":"123456"}
            """));
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

    @Test
    void shouldRecordIdempotentStudentSubmissionsAndEnforceServerState() throws Exception {
        Path database = start();
        JsonNode teacher = register("submit-teacher@example.edu", "Submit Teacher");
        JsonNode student = register("submit-student@example.edu", "Submit Student");
        JsonNode outsider = register("submit-outsider@example.edu", "Submit Outsider");
        promoteTeacher(database, teacher.at("/user/id").asText());
        String teacherToken = teacher.get("accessToken").asText();
        String studentToken = student.get("accessToken").asText();
        String classroomId = post("classes", teacherToken, "{\"name\":\"Submission class\"}")
            .get("id").asText();
        post("classes/" + classroomId + "/members", teacherToken,
            "{\"email\":\"submit-student@example.edu\",\"role\":\"STUDENT\"}");
        JsonNode assignment = post("classes/" + classroomId + "/assignments", teacherToken,
            "{\"exerciseId\":\"deterministic-1\",\"title\":\"Deterministic task\",\"status\":\"PUBLISHED\"}");
        String submissionPath = "classes/" + classroomId + "/assignments/"
            + assignment.get("id").asText() + "/submissions";
        String firstBody = JSON.writeValueAsString(java.util.Map.of(
            "operationId", "operation-0001", "passed", true, "resultHash", "a".repeat(64),
            "clientCompletedAt", "2099-01-01T00:00:00Z", "sql", "DROP DATABASE forbidden"));

        JsonNode first = post(submissionPath, studentToken, firstBody);
        JsonNode duplicate = post(submissionPath, studentToken, firstBody);
        JsonNode second = post(submissionPath, studentToken, JSON.writeValueAsString(java.util.Map.of(
            "operationId", "operation-0002", "passed", false, "resultHash", "b".repeat(64),
            "errorCode", "RESULT_MISMATCH")));

        assertEquals(first.get("id").asText(), duplicate.get("id").asText());
        assertEquals(1, first.get("attemptNumber").asInt());
        assertEquals(2, second.get("attemptNumber").asInt());
        assertEquals("PASSED", first.get("status").asText());
        assertEquals("FAILED", second.get("status").asText());
        assertEquals("RESULT_MISMATCH", second.get("errorCode").asText());
        assertTrue(Instant.parse(first.get("submittedAt").asText()).isBefore(Instant.parse("2099-01-01T00:00:00Z")));
        assertEquals(2, JSON.readTree(getText(submissionPath, studentToken)).get("submissions").size());

        server.stop();
        server = new SqlTeacherCloudServer(database, 0);
        server.start();
        JsonNode duplicateAfterRestart = post(submissionPath, studentToken, firstBody);
        assertEquals(first.get("id").asText(), duplicateAfterRestart.get("id").asText());

        assertEquals(403, send("POST", submissionPath, teacherToken, firstBody).statusCode());
        assertEquals(403, send("POST", submissionPath, outsider.get("accessToken").asText(), firstBody).statusCode());

        JsonNode otherAssignment = post("classes/" + classroomId + "/assignments", teacherToken,
            "{\"exerciseId\":\"deterministic-2\",\"title\":\"Other task\",\"status\":\"PUBLISHED\"}");
        HttpResponse<String> operationConflict = send("POST", "classes/" + classroomId + "/assignments/"
            + otherAssignment.get("id").asText() + "/submissions", studentToken, firstBody);
        assertEquals(409, operationConflict.statusCode());
        assertEquals("SUBMISSION_OPERATION_CONFLICT", JSON.readTree(operationConflict.body()).get("code").asText());

        JsonNode closed = post("classes/" + classroomId + "/assignments/" + assignment.get("id").asText()
            + "/status", teacherToken, JSON.writeValueAsString(java.util.Map.of(
                "status", "CLOSED", "expectedVersion", assignment.get("version").asLong())));
        HttpResponse<String> closedSubmission = send("POST", submissionPath, studentToken,
            JSON.writeValueAsString(java.util.Map.of(
                "operationId", "operation-0003", "passed", true, "resultHash", "c".repeat(64))));
        assertEquals(409, closedSubmission.statusCode());
        assertEquals("ASSIGNMENT_CLOSED", JSON.readTree(closedSubmission.body()).get("code").asText());
        assertEquals("CLOSED", closed.get("status").asText());

        JsonNode archived = post("classes/" + classroomId + "/assignments/" + assignment.get("id").asText()
            + "/status", teacherToken, JSON.writeValueAsString(java.util.Map.of(
                "status", "ARCHIVED", "expectedVersion", closed.get("version").asLong())));
        HttpResponse<String> archivedSubmission = send("POST", submissionPath, studentToken,
            JSON.writeValueAsString(java.util.Map.of(
                "operationId", "operation-0004", "passed", true, "resultHash", "d".repeat(64))));
        assertEquals("ARCHIVED", archived.get("status").asText());
        assertEquals("ASSIGNMENT_ARCHIVED", JSON.readTree(archivedSubmission.body()).get("code").asText());

        JsonNode withdrawn = post("classes/" + classroomId + "/assignments/" + otherAssignment.get("id").asText()
            + "/status", teacherToken, JSON.writeValueAsString(java.util.Map.of(
                "status", "WITHDRAWN", "expectedVersion", otherAssignment.get("version").asLong())));
        HttpResponse<String> withdrawnSubmission = send("POST", "classes/" + classroomId + "/assignments/"
            + otherAssignment.get("id").asText() + "/submissions", studentToken,
            JSON.writeValueAsString(java.util.Map.of(
                "operationId", "operation-0005", "passed", true, "resultHash", "e".repeat(64))));
        assertEquals("WITHDRAWN", withdrawn.get("status").asText());
        assertEquals("ASSIGNMENT_WITHDRAWN", JSON.readTree(withdrawnSubmission.body()).get("code").asText());

        JsonNode deadlineAssignment = post("classes/" + classroomId + "/assignments", teacherToken,
            JSON.writeValueAsString(java.util.Map.of(
                "exerciseId", "deadline", "title", "Deadline task", "status", "PUBLISHED",
                "dueAt", Instant.now().plusSeconds(1).toString())));
        Thread.sleep(1_100);
        HttpResponse<String> lateSubmission = send("POST", "classes/" + classroomId + "/assignments/"
            + deadlineAssignment.get("id").asText() + "/submissions", studentToken,
            JSON.writeValueAsString(java.util.Map.of(
                "operationId", "operation-0006", "passed", true, "resultHash", "f".repeat(64),
                "clientCompletedAt", Instant.now().minusSeconds(60).toString())));
        assertEquals(409, lateSubmission.statusCode());
        assertEquals("ASSIGNMENT_CLOSED", JSON.readTree(lateSubmission.body()).get("code").asText());
    }

    @Test
    void shouldReportPageAndExportAssignmentAnalyticsWithOneFilterScope() throws Exception {
        Path database = start();
        JsonNode teacher = register("analytics-teacher@example.edu", "Analytics Teacher");
        JsonNode otherTeacher = register("analytics-other@example.edu", "Other Teacher");
        JsonNode passedStudent = register("analytics-passed@example.edu", "Passed Student");
        JsonNode failedStudent = register("analytics-failed@example.edu", "Failed Student");
        register("analytics-missing@example.edu", "=FORMULA");
        promoteTeacher(database, teacher.at("/user/id").asText());
        promoteTeacher(database, otherTeacher.at("/user/id").asText());
        String teacherToken = teacher.get("accessToken").asText();
        String classroomId = post("classes", teacherToken, "{\"name\":\"Analytics class\"}")
            .get("id").asText();
        for (String email : java.util.List.of(
            "analytics-passed@example.edu", "analytics-failed@example.edu", "analytics-missing@example.edu")) {
            post("classes/" + classroomId + "/members", teacherToken,
                JSON.writeValueAsString(java.util.Map.of("email", email, "role", "STUDENT")));
        }
        JsonNode assignment = post("classes/" + classroomId + "/assignments", teacherToken,
            "{\"exerciseId\":\"analytics-task\",\"title\":\"Analytics task\",\"status\":\"PUBLISHED\"}");
        String assignmentBase = "classes/" + classroomId + "/assignments/" + assignment.get("id").asText();
        post(assignmentBase + "/submissions", passedStudent.get("accessToken").asText(), submissionJson(
            "analytics-op-1", false, "1", "RESULT_MISMATCH"));
        post(assignmentBase + "/submissions", passedStudent.get("accessToken").asText(), submissionJson(
            "analytics-op-2", true, "2", null));
        post(assignmentBase + "/submissions", failedStudent.get("accessToken").asText(), submissionJson(
            "analytics-op-3", false, "3", "RESULT_MISMATCH"));

        JsonNode report = JSON.readTree(getText(assignmentBase + "/analytics?page=0&pageSize=2", teacherToken));
        assertEquals(3, report.get("totalStudents").asInt());
        assertEquals(2, report.get("submittedStudents").asInt());
        assertEquals(1, report.get("passedStudents").asInt());
        assertEquals(3, report.get("totalAttempts").asInt());
        assertEquals(2.0 / 3.0, report.get("completionRate").asDouble(), 0.0001);
        assertEquals(0.5, report.get("passRate").asDouble(), 0.0001);
        assertEquals(3, report.get("totalRows").asInt());
        assertEquals(2, report.get("rows").size());
        assertEquals("RESULT_MISMATCH", report.at("/commonErrors/0/errorCode").asText());
        assertEquals(2, report.at("/commonErrors/0/count").asInt());
        JsonNode secondPage = JSON.readTree(getText(
            assignmentBase + "/analytics?page=1&pageSize=2", teacherToken));
        assertEquals(1, secondPage.get("rows").size());
        assertEquals(3, secondPage.get("totalRows").asInt());

        JsonNode futureWindow = JSON.readTree(getText(assignmentBase
            + "/analytics?from=2099-01-01T00:00:00Z&page=0&pageSize=50", teacherToken));
        assertEquals(0, futureWindow.get("submittedStudents").asInt());
        assertEquals(0, futureWindow.get("totalAttempts").asInt());

        JsonNode failed = JSON.readTree(getText(
            assignmentBase + "/analytics?status=FAILED&page=0&pageSize=50", teacherToken));
        assertEquals(1, failed.get("totalRows").asInt());
        assertEquals("analytics-failed@example.edu", failed.at("/rows/0/email").asText());
        String failedCsv = getText(assignmentBase + "/analytics/export?status=FAILED&page=0&pageSize=50",
            teacherToken);
        assertTrue(failedCsv.contains("analytics-failed@example.edu"));
        assertTrue(!failedCsv.contains("analytics-passed@example.edu"));

        String missingCsv = getText(
            assignmentBase + "/analytics/export?status=NOT_SUBMITTED&page=0&pageSize=50", teacherToken);
        assertTrue(missingCsv.contains("\"'=FORMULA\""));
        assertEquals(403, getStatus(assignmentBase + "/analytics", passedStudent.get("accessToken").asText()));
        assertEquals(403, getStatus(assignmentBase + "/analytics", otherTeacher.get("accessToken").asText()));

        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.prepareStatement(
                 "select assignment_id,export_type,filter_summary,row_count from export_audit "
                     + "where export_type='ASSIGNMENT_ANALYTICS' order by created_at desc limit 1");
             var audit = statement.executeQuery()) {
            assertTrue(audit.next());
            assertEquals(assignment.get("id").asText(), audit.getString("assignment_id"));
            assertEquals("ASSIGNMENT_ANALYTICS", audit.getString("export_type"));
            assertTrue(audit.getString("filter_summary").contains("status=NOT_SUBMITTED"));
            assertEquals(1, audit.getInt("row_count"));
        }
    }

    @Test
    void shouldEnforceAuditedAdministratorAccountOperations() throws Exception {
        Path database = start();
        JsonNode admin = register("admin-ops@example.edu", "Operations Admin");
        JsonNode user = register("managed-user@example.edu", "Managed User");
        promoteAdmin(database, admin.at("/user/id").asText());
        String adminToken = admin.get("accessToken").asText();
        String userToken = user.get("accessToken").asText();
        String userId = user.at("/user/id").asText();

        JsonNode health = JSON.readTree(getText("admin/health", adminToken));
        assertEquals(2, health.get("activeUsers").asInt());
        assertEquals(0, health.get("disabledUsers").asInt());
        assertTrue(health.get("activeAccessSessions").asInt() >= 2);
        assertEquals(403, getStatus("admin/health", userToken));
        JsonNode knowledgeIndex = JSON.readTree(getText("admin/knowledge-index", adminToken));
        assertEquals("disabled", knowledgeIndex.get("status").asText());
        assertEquals(403, getStatus("admin/knowledge-index", userToken));
        assertEquals(2, JSON.readTree(getText("admin/users", adminToken)).get("users").size());

        JsonNode disabled = post("admin/users/" + userId + "/disable", adminToken,
            "{\"reasonCode\":\"POLICY_VIOLATION\"}");
        assertTrue(disabled.get("disabled").asBoolean());
        assertEquals(403, getStatus("classes", userToken));
        assertEquals(401, postStatus("auth/login", null,
            "{\"email\":\"managed-user@example.edu\",\"password\":\"strong-password-123\"}"));

        JsonNode restored = post("admin/users/" + userId + "/restore", adminToken,
            "{\"reasonCode\":\"REVIEW_COMPLETE\"}");
        assertTrue(!restored.get("disabled").asBoolean());
        JsonNode relogged = post("auth/login", null,
            "{\"email\":\"managed-user@example.edu\",\"password\":\"strong-password-123\"}");
        post("admin/users/" + userId + "/revoke-sessions", adminToken,
            "{\"reasonCode\":\"SECURITY_RESET\"}");
        assertEquals(403, getStatus("classes", relogged.get("accessToken").asText()));
        assertEquals(401, postStatus("auth/refresh", null, JSON.writeValueAsString(java.util.Map.of(
            "refreshToken", relogged.get("refreshToken").asText()))));

        HttpResponse<String> lastAdmin = send("POST", "admin/users/" + admin.at("/user/id").asText()
            + "/disable", adminToken, "{\"reasonCode\":\"TEST_LAST_ADMIN\"}");
        assertEquals(409, lastAdmin.statusCode());
        assertEquals("LAST_ADMIN_PROTECTED", JSON.readTree(lastAdmin.body()).get("code").asText());

        JsonNode classroom = post("classes", adminToken, "{\"name\":\"Audited class\"}");
        JsonNode assignment = post("classes/" + classroom.get("id").asText() + "/assignments", adminToken,
            "{\"exerciseId\":\"audit-task\",\"title\":\"Audited task\",\"status\":\"DRAFT\"}");
        getText("classes/" + classroom.get("id").asText() + "/assignments/" + assignment.get("id").asText()
            + "/analytics/export?page=0&pageSize=50", adminToken);

        JsonNode disableAudit = JSON.readTree(getText(
            "admin/audit?action=ADMIN_USER_DISABLE&page=0&pageSize=50", adminToken));
        assertTrue(disableAudit.get("totalRows").asInt() >= 2);
        assertTrue(disableAudit.toString().contains("POLICY_VIOLATION"));
        assertTrue(disableAudit.toString().contains("LAST_ADMIN_PROTECTED"));
        JsonNode authAudit = JSON.readTree(getText(
            "admin/audit?action=AUTH_LOGIN&page=0&pageSize=50", adminToken));
        assertTrue(authAudit.get("totalRows").asInt() >= 2);
        assertTrue(!authAudit.toString().toLowerCase(java.util.Locale.ROOT).contains("password"));
        JsonNode taskAudit = JSON.readTree(getText(
            "admin/audit?action=ASSIGNMENT_CREATE&page=0&pageSize=50", adminToken));
        assertEquals(1, taskAudit.get("totalRows").asInt());
        JsonNode exportAudit = JSON.readTree(getText(
            "admin/audit?action=ASSIGNMENT_ANALYTICS_EXPORT&page=0&pageSize=50", adminToken));
        assertEquals(1, exportAudit.get("totalRows").asInt());
    }

    @Test
    void shouldPreviewArchiveRestoreAndBlockChangedRetentionScope() throws Exception {
        Path database = start();
        JsonNode admin = register("retention-admin@example.edu", "Retention Admin");
        promoteAdmin(database, admin.at("/user/id").asText());
        String adminToken = admin.get("accessToken").asText();
        String userId = admin.at("/user/id").asText();
        insertSyncEvent(database, userId, "old-event", "2025-01-01T00:00:00Z");
        insertSyncEvent(database, userId, "new-event", "2026-07-28T00:00:00Z");

        JsonNode preview = post("admin/retention/preview", adminToken,
            "{\"category\":\"SYNC_EVENTS\",\"cutoff\":\"2025-07-01T00:00:00Z\"}");

        assertEquals(1, preview.get("affectedRows").asInt());
        assertEquals(409, postStatus("admin/retention/execute", adminToken,
            JSON.writeValueAsString(java.util.Map.of(
                "previewId", preview.get("id").asText(), "confirmationToken", "wrong-token",
                "backupReference", "cloud-20260728.db"))));

        JsonNode job = post("admin/retention/execute", adminToken,
            JSON.writeValueAsString(java.util.Map.of(
                "previewId", preview.get("id").asText(),
                "confirmationToken", preview.get("confirmationToken").asText(),
                "backupReference", "cloud-20260728.db")));

        assertEquals("COMPLETED", job.get("status").asText());
        assertEquals(1, job.get("affectedRows").asInt());
        assertEquals(1, tableCount(database, "sync_events"));
        assertEquals(1, tableCount(database, "retention_archive"));
        assertTrue(java.nio.file.Files.exists(database.getParent().resolve("retention-backups")
            .resolve("retention-" + job.get("id").asText() + ".db")));

        JsonNode restored = post("admin/retention/" + job.get("id").asText() + "/restore",
            adminToken, "{}");

        assertEquals("RESTORED", restored.get("status").asText());
        assertEquals(2, tableCount(database, "sync_events"));

        JsonNode changedPreview = post("admin/retention/preview", adminToken,
            "{\"category\":\"SYNC_EVENTS\",\"cutoff\":\"2025-07-01T00:00:00Z\"}");
        insertSyncEvent(database, userId, "concurrent-old-event", "2025-02-01T00:00:00Z");
        HttpResponse<String> changed = send("POST", "admin/retention/execute", adminToken,
            JSON.writeValueAsString(java.util.Map.of(
                "previewId", changedPreview.get("id").asText(),
                "confirmationToken", changedPreview.get("confirmationToken").asText(),
                "backupReference", "cloud-20260728.db")));
        assertEquals(409, changed.statusCode());
        assertEquals("RETENTION_SCOPE_CHANGED", JSON.readTree(changed.body()).get("code").asText());
        assertEquals(3, tableCount(database, "sync_events"));
    }

    @Test
    void shouldDeliverVersionedCourseFeedbackMasteryAndNotifications() throws Exception {
        Path database = start();
        JsonNode teacher = register("v14-teacher@example.edu", "V14 Teacher");
        JsonNode student = register("v14-student@example.edu", "V14 Student");
        promoteTeacher(database, teacher.at("/user/id").asText());
        String teacherToken = teacher.get("accessToken").asText();
        String studentToken = student.get("accessToken").asText();
        String studentId = student.at("/user/id").asText();

        String classroomId = post("classes", teacherToken, "{\"name\":\"V14 class\"}").get("id").asText();
        post("classes/" + classroomId + "/members", teacherToken,
            "{\"email\":\"v14-student@example.edu\",\"role\":\"STUDENT\"}");

        JsonNode course = post("v14/courses", teacherToken,
            "{\"name\":\"SQL 基础\",\"description\":\"共享课程\"}");
        String courseId = course.get("id").asText();
        JsonNode section = post("v14/courses/" + courseId + "/sections", teacherToken,
            "{\"name\":\"查询基础\",\"sortOrder\":1}");
        JsonNode point = post("v14/courses/" + courseId + "/knowledge-points", teacherToken,
            JSON.writeValueAsString(java.util.Map.of("sectionId", section.get("id").asText(),
                "name", "WHERE 过滤", "description", "条件过滤", "sortOrder", 1)));
        String pointId = point.get("id").asText();

        java.util.Map<String, Object> exerciseBody = new java.util.LinkedHashMap<>();
        exerciseBody.put("exerciseId", "select-1");
        exerciseBody.put("title", "筛选学生");
        exerciseBody.put("prompt", "查询分数大于 80 的学生");
        exerciseBody.put("datasetVersion", "student-v1");
        exerciseBody.put("evaluationRule", "RESULT_SET");
        exerciseBody.put("knowledgePointIds", java.util.List.of(pointId));
        exerciseBody.put("operationId", "publish-exercise-0001");
        JsonNode versionOne = post("v14/courses/" + courseId + "/exercises", teacherToken,
            JSON.writeValueAsString(exerciseBody));
        JsonNode repeated = post("v14/courses/" + courseId + "/exercises", teacherToken,
            JSON.writeValueAsString(exerciseBody));
        assertEquals(versionOne.get("id").asText(), repeated.get("id").asText());

        JsonNode assignment = post("v14/classes/" + classroomId + "/assignments/from-version", teacherToken,
            JSON.writeValueAsString(java.util.Map.of("exerciseVersionId", versionOne.get("id").asText(),
                "title", "第一次课堂任务", "description", "完成筛选", "operationId", "assignment-v14-0001",
                "dueAt", Instant.now().plusSeconds(7_200).toString())));
        String assignmentId = assignment.get("id").asText();
        JsonNode snapshot = JSON.readTree(getText(
            "v14/classes/" + classroomId + "/assignments/" + assignmentId + "/snapshot", studentToken));
        assertEquals(versionOne.get("id").asText(), snapshot.get("exerciseVersionId").asText());
        String originalSnapshotHash = snapshot.get("snapshotHash").asText();

        JsonNode notifications = JSON.readTree(getText("v14/notifications?page=0&pageSize=50", studentToken));
        assertTrue(java.util.stream.StreamSupport.stream(notifications.get("notifications").spliterator(), false)
            .anyMatch(item -> "ASSIGNMENT_PUBLISHED".equals(item.get("type").asText())));

        JsonNode submission = post("classes/" + classroomId + "/assignments/" + assignmentId + "/submissions",
            studentToken, submissionJson("v14-submit-0001", false, "f", "FILTER_MISMATCH"));
        String submissionId = submission.get("id").asText();
        JsonNode draft = JSON.readTree(getText("v14/classes/" + classroomId + "/assignments/" + assignmentId
            + "/submissions/" + submissionId + "/feedback-draft", teacherToken));
        assertTrue(draft.get("text").asText().contains("FILTER_MISMATCH"));
        assertEquals(false, draft.get("aiGenerated").asBoolean());

        JsonNode feedback = post("v14/classes/" + classroomId + "/assignments/" + assignmentId + "/feedback",
            teacherToken, JSON.writeValueAsString(java.util.Map.of("submissionId", submissionId,
                "status", "NEEDS_WORK", "comment", "请检查 WHERE 条件", "knowledgePointIds", java.util.List.of(pointId),
                "expectedVersion", 0, "operationId", "feedback-v14-0001")));
        assertEquals(1, feedback.get("version").asLong());
        assertEquals(409, postStatus("v14/classes/" + classroomId + "/assignments/" + assignmentId + "/feedback",
            teacherToken, JSON.writeValueAsString(java.util.Map.of("submissionId", submissionId,
                "status", "REVIEWED", "comment", "stale", "knowledgePointIds", java.util.List.of(pointId),
                "expectedVersion", 0, "operationId", "feedback-v14-stale"))));

        JsonNode studentFeedback = JSON.readTree(getText(
            "v14/classes/" + classroomId + "/assignments/" + assignmentId + "/feedback", studentToken));
        assertEquals(1, studentFeedback.get("feedback").size());
        JsonNode mastery = JSON.readTree(getText(
            "v14/classes/" + classroomId + "/mastery?studentUserId=" + studentId, teacherToken));
        assertEquals(1, mastery.get("mastery").size());
        assertEquals(0, mastery.at("/mastery/0/masteryPercent").asInt());
        assertEquals(1, mastery.at("/mastery/0/recommendations").size());

        exerciseBody.put("prompt", "查询分数至少为 80 的学生");
        exerciseBody.put("operationId", "publish-exercise-0002");
        JsonNode versionTwo = post("v14/courses/" + courseId + "/exercises", teacherToken,
            JSON.writeValueAsString(exerciseBody));
        assertEquals(2, versionTwo.get("version").asInt());
        JsonNode unchanged = JSON.readTree(getText(
            "v14/classes/" + classroomId + "/assignments/" + assignmentId + "/snapshot", studentToken));
        assertEquals(originalSnapshotHash, unchanged.get("snapshotHash").asText());

        JsonNode bundle = JSON.readTree(getText("v14/courses/" + courseId + "/export", teacherToken));
        JsonNode imported = post("v14/courses/import", teacherToken, JSON.writeValueAsString(java.util.Map.of(
            "bundleJson", bundle.get("bundleJson").asText(), "operationId", "import-v14-0001")));
        assertEquals(1, imported.get("sections").asInt());
        assertEquals(1, imported.get("knowledgePoints").asInt());
        assertEquals(1, imported.get("exercises").asInt());

        JsonNode allNotifications = JSON.readTree(getText("v14/notifications?page=0&pageSize=50", studentToken));
        assertTrue(allNotifications.get("notifications").size() >= 3);
        String notificationId = allNotifications.at("/notifications/0/id").asText();
        JsonNode read = post("v14/notifications/" + notificationId + "/read", studentToken, "{}");
        assertTrue(!read.get("readAt").isNull());
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

    private String submissionJson(String operationId, boolean passed, String hashCharacter, String errorCode)
        throws Exception {
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("operationId", operationId);
        body.put("passed", passed);
        body.put("resultHash", hashCharacter.repeat(64));
        if (errorCode != null) body.put("errorCode", errorCode);
        return JSON.writeValueAsString(body);
    }

    private void promoteTeacher(Path database, String userId) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.prepareStatement(
                 "insert or ignore into user_roles(user_id,role) values(?, 'TEACHER')")) {
            statement.setString(1, userId);
            statement.executeUpdate();
        }
    }

    private void promoteAdmin(Path database, String userId) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.prepareStatement(
                 "insert or ignore into user_roles(user_id,role) values(?, 'ADMIN')")) {
            statement.setString(1, userId);
            statement.executeUpdate();
        }
    }

    private void insertSyncEvent(Path database, String userId, String eventId, String occurredAt) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.prepareStatement(
                 "insert into sync_events(user_id,event_id,event_type,payload_json,occurred_at) values(?,?,?,?,?)")) {
            statement.setString(1, userId);
            statement.setString(2, eventId);
            statement.setString(3, "PRACTICE_COMPLETED");
            statement.setString(4, "{\"successful\":true}");
            statement.setString(5, occurredAt);
            statement.executeUpdate();
        }
    }

    private int tableCount(Path database, String table) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement();
             var row = statement.executeQuery("select count(*) from " + table)) {
            return row.getInt(1);
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

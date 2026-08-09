package com.sqlteacher.server;

import com.sqlteacher.application.collaboration.AssignmentSubmissionRequest;
import com.sqlteacher.application.collaboration.FeedbackStatus;
import com.sqlteacher.application.collaboration.ContentStatus;
import com.sqlteacher.application.collaboration.CloudApiRequestException;
import com.sqlteacher.application.collaboration.CloudArtifactSyncItem;
import com.sqlteacher.application.collaboration.CloudArtifactSyncResult;
import com.sqlteacher.application.collaboration.NotificationType;
import com.sqlteacher.application.collaboration.UserRole;
import com.sqlteacher.application.planning.ObjectiveResourceType;
import com.sqlteacher.application.planning.StudyPlanReasonCode;
import com.sqlteacher.application.planning.StudyPlanActionState;
import com.sqlteacher.infrastructure.cloud.HttpCloudApiClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("integration")
class V14CloudApiClientIntegrationTest {
    @TempDir Path directory;
    private SqlTeacherCloudServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop();
    }

    @Test
    void shouldMapTheCompleteV14ClientWorkflow() throws Exception {
        Path database = directory.resolve("cloud.db");
        server = new SqlTeacherCloudServer(database, 0);
        server.start();
        HttpCloudApiClient client = new HttpCloudApiClient(
            URI.create("http://127.0.0.1:" + server.port()));

        var teacherSession = client.register("client-teacher@example.edu", "Teacher",
            "strong-password-123".toCharArray());
        var studentSession = client.register("client-student@example.edu", "Student",
            "strong-password-123".toCharArray());
        promoteTeacher(database, teacherSession.user().id());
        String teacher = teacherSession.accessToken();
        String student = studentSession.accessToken();

        var classroom = client.createClass(teacher, "Client workflow");
        client.addClassMember(teacher, classroom.id(), "client-student@example.edu", UserRole.STUDENT);
        var course = client.createCourse(teacher, "SQL Client", "client mapping");
        var section = client.createCourseSection(teacher, course.id(), "SELECT", 1);
        var point = client.createKnowledgePoint(teacher, course.id(), section.id(), "过滤", "WHERE", 1);
        var publishedKnowledge = client.publishCloudKnowledge(teacher, course.id(), section.id(), "WHERE 课程资料",
            "WHERE 子句在聚合前筛选行。", "PUBLISHED");
        client.publishCloudKnowledge(teacher, course.id(), section.id(), "教师私有草稿", "私有答案", "PRIVATE");
        assertEquals(2, client.listCloudKnowledge(teacher, course.id()).size());
        assertEquals(List.of(publishedKnowledge), client.listCloudKnowledge(student, course.id()));
        assertEquals(1, client.searchCloudKnowledge(student, course.id(), "WHERE", 10).size());
        assertTrue(client.searchCloudKnowledge(student, course.id(), "私有答案", 10).isEmpty());
        assertEquals(2, client.updateCourseSection(teacher, course.id(), section.id(), "SELECT 查询", 2,
            ContentStatus.ACTIVE, section.version()).version());
        assertEquals(2, client.updateKnowledgePoint(teacher, course.id(), point.id(), section.id(), "WHERE 过滤",
            "条件过滤", 2, ContentStatus.ACTIVE, point.version()).version());
        var version = client.publishSharedExercise(teacher, course.id(), "select-1", "过滤题",
            "筛选数据", "dataset-v1", "RESULT_SET", List.of(point.id()), UUID.randomUUID().toString());
        assertEquals(1, client.listSharedExercises(teacher, course.id(), point.id()).size());
        client.setSharedExerciseStatus(teacher, course.id(), version.exerciseId(), ContentStatus.INACTIVE);
        assertThrows(CloudApiRequestException.class, () -> client.createAssignmentFromVersion(teacher,
            classroom.id(), version.id(), "不应创建", "", Instant.now().plusSeconds(3_600),
            UUID.randomUUID().toString()));
        client.setSharedExerciseStatus(teacher, course.id(), version.exerciseId(), ContentStatus.ACTIVE);

        var basicsObjective = client.createCourseObjective(teacher, course.id(), "掌握 WHERE 过滤", "理解条件筛选",
            "能够完成 WHERE 练习", 1);
        var advancedObjective = client.createCourseObjective(teacher, course.id(), "组合过滤条件", "组合多个条件",
            "能够完成组合过滤练习", 2);
        String basicsObjectiveId = basicsObjective.id();
        client.addObjectiveResource(teacher, course.id(), basicsObjective.id(),
            ObjectiveResourceType.KNOWLEDGE_ARTICLE, publishedKnowledge.id());
        client.addObjectiveResource(teacher, course.id(), basicsObjective.id(),
            ObjectiveResourceType.KNOWLEDGE_POINT, point.id());
        client.addObjectiveResource(teacher, course.id(), advancedObjective.id(),
            ObjectiveResourceType.EXERCISE_VERSION, version.id());
        client.addObjectivePrerequisite(teacher, course.id(), advancedObjective.id(), basicsObjective.id());
        assertThrows(CloudApiRequestException.class, () -> client.addObjectivePrerequisite(teacher, course.id(),
            basicsObjectiveId, advancedObjective.id()));
        assertEquals(2, client.listCourseObjectives(student, course.id()).size());
        var studentPlan = client.getStudyPlan(student, course.id());
        assertEquals("v1.9.0-r1", studentPlan.policyVersion());
        assertEquals(basicsObjective.id(), studentPlan.actions().getFirst().objectiveId());
        assertEquals(StudyPlanReasonCode.PREREQUISITE_GAP, studentPlan.actions().getFirst().reasonCode());
        var startedPlanAction = client.updateStudyPlanAction(student, course.id(),
            studentPlan.actions().getFirst().id(), StudyPlanActionState.STARTED, 0, UUID.randomUUID().toString());
        assertEquals(1, startedPlanAction.version());
        assertEquals(StudyPlanActionState.STARTED, client.getStudyPlan(student, course.id()).actions().getFirst().state());
        var originalObjective = basicsObjective;
        basicsObjective = client.updateCourseObjective(teacher, course.id(), basicsObjective.id(),
            "掌握 WHERE 条件过滤", basicsObjective.description(), basicsObjective.completionCriteria(),
            basicsObjective.sortOrder(), ContentStatus.ACTIVE, basicsObjective.version());
        assertEquals(2, basicsObjective.version());
        assertThrows(CloudApiRequestException.class, () -> client.updateCourseObjective(teacher, course.id(),
            originalObjective.id(), originalObjective.title(), originalObjective.description(),
            originalObjective.completionCriteria(), originalObjective.sortOrder(), ContentStatus.ACTIVE,
            originalObjective.version()));

        var assignment = client.createAssignmentFromVersion(teacher, classroom.id(), version.id(),
            "客户端任务", "验证映射", Instant.now().plusSeconds(3_600), UUID.randomUUID().toString());
        assertEquals(version.id(), client.getAssignmentContentSnapshot(student, classroom.id(), assignment.id())
            .exerciseVersionId());
        var otherClass = client.createClass(teacher, "Other class");
        client.addClassMember(teacher, otherClass.id(), "client-student@example.edu", UserRole.STUDENT);
        assertThrows(CloudApiRequestException.class, () -> client.getAssignmentContentSnapshot(student,
            otherClass.id(), assignment.id()));
        assertTrue(client.listSubmissionFeedback(student, otherClass.id(), assignment.id()).isEmpty());
        var submission = client.submitAssignment(student, classroom.id(), assignment.id(),
            new AssignmentSubmissionRequest(UUID.randomUUID().toString(), false, "a".repeat(64),
                "FILTER_MISMATCH", Instant.now()));
        assertFalse(client.draftSubmissionFeedback(teacher, classroom.id(), assignment.id(), submission.id())
            .aiGenerated());
        var feedback = client.saveSubmissionFeedback(teacher, classroom.id(), assignment.id(), submission.id(),
            FeedbackStatus.NEEDS_WORK, "检查过滤条件", List.of(point.id()), 0, UUID.randomUUID().toString());
        assertEquals(1, feedback.version());
        assertEquals(1, client.listSubmissionFeedback(student, classroom.id(), assignment.id()).size());
        assertEquals(0, client.getKnowledgeMastery(student, classroom.id(), null).getFirst().masteryPercent());

        var objectiveSummary = client.getObjectiveClassSummary(teacher, course.id(), classroom.id());
        assertEquals(1, objectiveSummary.getFirst().totalStudents());
        assertEquals(1, objectiveSummary.stream().filter(item -> item.objectiveId().equals(basicsObjectiveId))
            .findFirst().orElseThrow().needsSupport());
        var staleDraft = client.createObjectiveInterventionDraft(teacher, course.id(), classroom.id(),
            basicsObjective.id(), "OBJECTIVE_EVIDENCE_GAP", "发布 WHERE 复习资料草稿");
        basicsObjective = client.updateCourseObjective(teacher, course.id(), basicsObjective.id(),
            basicsObjective.title(), basicsObjective.description(), basicsObjective.completionCriteria(),
            basicsObjective.sortOrder(), ContentStatus.ACTIVE, basicsObjective.version());
        var invalidatedDraft = staleDraft;
        assertThrows(CloudApiRequestException.class, () -> client.confirmObjectiveInterventionDraft(teacher,
            course.id(), invalidatedDraft.id(), invalidatedDraft.confirmationToken()));
        var currentDraft = client.createObjectiveInterventionDraft(teacher, course.id(), classroom.id(),
            basicsObjective.id(), "OBJECTIVE_EVIDENCE_GAP", "发布 WHERE 复习资料草稿");
        assertEquals("CONFIRMED", client.confirmObjectiveInterventionDraft(teacher, course.id(), currentDraft.id(),
            currentDraft.confirmationToken()).status());
        addSyntheticStudents(database, classroom.id(), 499);
        long summaryStarted = System.nanoTime();
        var capacitySummary = client.getObjectiveClassSummary(teacher, course.id(), classroom.id());
        long summaryMillis = java.time.Duration.ofNanos(System.nanoTime() - summaryStarted).toMillis();
        assertEquals(500, capacitySummary.getFirst().totalStudents());
        assertTrue(summaryMillis < 2_000, "500-student objective summary took " + summaryMillis + " ms");
        promoteAdmin(database, teacherSession.user().id());
        assertEquals(2, client.getPlanningHealth(teacher).activeObjectives());
        assertEquals(1, client.getPlanningHealth(teacher).confirmedInterventions());

        var notifications = client.listNotifications(student, 0, 50);
        assertTrue(notifications.stream().anyMatch(item -> item.type() == NotificationType.ASSIGNMENT_PUBLISHED));
        assertTrue(notifications.stream().anyMatch(item -> item.type() == NotificationType.SUBMISSION_CONFIRMED));
        assertTrue(notifications.stream().anyMatch(item -> item.type() == NotificationType.FEEDBACK_PUBLISHED));
        assertFalse(client.markNotificationRead(student, notifications.getFirst().id()).unread());

        String bundle = client.exportCourseBundle(teacher, course.id());
        String importOperation = UUID.randomUUID().toString();
        var imported = client.importCourseBundle(teacher, bundle, importOperation);
        assertEquals(1, imported.sections());
        assertEquals(1, imported.knowledgePoints());
        assertEquals(1, imported.exercises());
        assertEquals(imported.courseId(), client.importCourseBundle(teacher, bundle, importOperation).courseId());

        int courseCountBeforeFailure = tableCount(database, "courses");
        var invalidBundle = new ObjectMapper().readTree(bundle);
        ((com.fasterxml.jackson.databind.node.ObjectNode) invalidBundle.at("/exercises/0")).put("title", "");
        assertThrows(CloudApiRequestException.class, () -> client.importCourseBundle(teacher,
            invalidBundle.toString(), UUID.randomUUID().toString()));
        assertEquals(courseCountBeforeFailure, tableCount(database, "courses"));

        String packageJson = securePackage(bundle, "course:client-workflow", "1", "CC-BY-4.0");
        var preview = client.previewCoursePackage(teacher, packageJson);
        assertEquals("CC-BY-4.0", preview.license());
        assertEquals(1, preview.exercises());
        assertThrows(CloudApiRequestException.class, () -> client.importCoursePackage(teacher, packageJson,
            UUID.randomUUID().toString(), preview.contentSha256(), false));
        String secureOperation = UUID.randomUUID().toString();
        var secureImport = client.importCoursePackage(teacher, packageJson, secureOperation,
            preview.contentSha256(), true);
        assertEquals(secureImport.courseId(), client.importCoursePackage(teacher, packageJson, secureOperation,
            preview.contentSha256(), true).courseId());
        int coursesAfterSecureImport = tableCount(database, "courses");
        assertEquals(secureImport.courseId(), client.importCoursePackage(teacher, packageJson,
            UUID.randomUUID().toString(), preview.contentSha256(), true).courseId());
        assertEquals(coursesAfterSecureImport, tableCount(database, "courses"));
        assertEquals(com.sqlteacher.application.collaboration.CoursePackagePreview.Conflict.SAME_CONTENT,
            client.previewCoursePackage(teacher, packageJson).conflict());
        var tampered = new ObjectMapper().readTree(packageJson);
        ((com.fasterxml.jackson.databind.node.ObjectNode) tampered).put("payloadJson",
            bundle.replace("SQL Client", "SQL Client changed"));
        assertThrows(CloudApiRequestException.class, () -> client.previewCoursePackage(teacher, tampered.toString()));

        assertTrue(client.capabilities().supports("EXPLICIT_SYNC_CONFLICTS"));
        String syncHash = "a".repeat(64);
        var syncV1 = new CloudArtifactSyncItem("sync-op-1", "PROJECT_METADATA", "project-1", 1,
            syncHash, "{\"resultHash\":\"" + syncHash + "\",\"reasonCode\":\"READY\"}", Instant.now(), 0);
        var accepted = client.uploadArtifactSyncItems(student, List.of(syncV1)).getFirst();
        assertEquals(CloudArtifactSyncResult.Status.ACCEPTED, accepted.status());
        assertEquals(CloudArtifactSyncResult.Status.DUPLICATE,
            client.uploadArtifactSyncItems(student, List.of(syncV1)).getFirst().status());
        var conflict = new CloudArtifactSyncItem("sync-op-conflict", "PROJECT_METADATA", "project-1", 1,
            "b".repeat(64), "{\"reasonCode\":\"CHANGED\"}", Instant.now(), 0);
        assertEquals("SYNC_STALE_VERSION", client.uploadArtifactSyncItems(student, List.of(conflict))
            .getFirst().conflictCode());
        assertEquals(1, client.downloadArtifactSyncItems(student, 0).items().size());
        var privatePayload = new CloudArtifactSyncItem("sync-private", "PROJECT_METADATA", "project-2", 1,
            syncHash, "{\"sourceCode\":\"private\"}", Instant.now(), 0);
        assertThrows(CloudApiRequestException.class,
            () -> client.uploadArtifactSyncItems(student, List.of(privatePayload)));

        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement();
             var row = statement.executeQuery("select max(version) from cloud_schema_version")) {
            assertEquals(6, row.getInt(1));
        }

        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.prepareStatement(
                 "delete from classroom_members where classroom_id=? and user_id=?")) {
            statement.setString(1, classroom.id());
            statement.setString(2, studentSession.user().id());
            statement.executeUpdate();
        }
        assertTrue(client.listNotifications(student, 0, 50).stream()
            .noneMatch(item -> item.resourceId().equals(assignment.id())));
    }

    private void promoteTeacher(Path database, String userId) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.prepareStatement(
                 "insert or ignore into user_roles(user_id,role) values(?, 'TEACHER')")) {
            statement.setString(1, userId);
            statement.executeUpdate();
        }
    }

    private int tableCount(Path database, String table) throws Exception {
        if (!List.of("courses").contains(table)) throw new IllegalArgumentException("Unexpected table");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement();
             var row = statement.executeQuery("select count(*) from " + table)) {
            return row.getInt(1);
        }
    }

    private static String securePackage(String payload, String packageId, String version, String license)
            throws Exception {
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(payload.getBytes(StandardCharsets.UTF_8)));
        return new ObjectMapper().writeValueAsString(Map.of("formatVersion", 2, "packageId", packageId,
            "courseVersion", version, "license", license, "contentSha256", sha, "payloadJson", payload));
    }

    private void promoteAdmin(Path database, String userId) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.prepareStatement(
                 "insert or ignore into user_roles(user_id,role) values(?, 'ADMIN')")) {
            statement.setString(1, userId);
            statement.executeUpdate();
        }
    }

    private void addSyntheticStudents(Path database, String classroomId, int count) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            connection.setAutoCommit(false);
            try (var user = connection.prepareStatement("""
                    insert into users(id,email,display_name,password_hash,password_salt,disabled,created_at)
                    values(?,?,?,?,?,0,?)
                    """);
                 var role = connection.prepareStatement(
                     "insert into user_roles(user_id,role) values(?,'STUDENT')");
                 var member = connection.prepareStatement(
                     "insert into classroom_members(classroom_id,user_id,role) values(?,?,'STUDENT')")) {
                for (int index = 0; index < count; index++) {
                    String id = "synthetic-student-" + index;
                    user.setString(1, id); user.setString(2, id + "@example.invalid");
                    user.setString(3, "Synthetic " + index); user.setBytes(4, new byte[]{1});
                    user.setBytes(5, new byte[]{1}); user.setString(6, Instant.EPOCH.toString()); user.addBatch();
                    role.setString(1, id); role.addBatch();
                    member.setString(1, classroomId); member.setString(2, id); member.addBatch();
                }
                user.executeBatch(); role.executeBatch(); member.executeBatch(); connection.commit();
            }
        }
    }
}

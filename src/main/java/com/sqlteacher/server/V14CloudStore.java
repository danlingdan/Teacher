package com.sqlteacher.server;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sqlteacher.application.collaboration.AssignmentContentSnapshot;
import com.sqlteacher.application.collaboration.AssignmentStatus;
import com.sqlteacher.application.collaboration.AssignmentSubmission;
import com.sqlteacher.application.collaboration.ClassAssignment;
import com.sqlteacher.application.collaboration.CloudNotification;
import com.sqlteacher.application.collaboration.CloudArtifactSyncItem;
import com.sqlteacher.application.collaboration.CloudArtifactSyncPage;
import com.sqlteacher.application.collaboration.CloudArtifactSyncResult;
import com.sqlteacher.application.collaboration.ContentStatus;
import com.sqlteacher.application.collaboration.CourseBundleImportResult;
import com.sqlteacher.application.collaboration.CoursePackagePreview;
import com.sqlteacher.application.collaboration.CourseCatalog;
import com.sqlteacher.application.collaboration.CourseSection;
import com.sqlteacher.application.collaboration.ExerciseRecommendation;
import com.sqlteacher.application.collaboration.FeedbackDraft;
import com.sqlteacher.application.collaboration.FeedbackStatus;
import com.sqlteacher.application.collaboration.KnowledgeMastery;
import com.sqlteacher.application.collaboration.KnowledgePoint;
import com.sqlteacher.application.collaboration.NotificationType;
import com.sqlteacher.application.collaboration.SharedExerciseVersion;
import com.sqlteacher.application.collaboration.SubmissionFeedback;
import com.sqlteacher.application.collaboration.UserRole;
import com.sqlteacher.application.collaboration.AuthenticatedUser;
import com.sqlteacher.application.collaboration.CloudKnowledgeArticle;
import com.sqlteacher.application.collaboration.CloudKnowledgeSearchHit;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** v1.4 course content, feedback, mastery and notification persistence. */
final class V14CloudStore {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules()
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private static final int MAX_PAGE_SIZE = 100;
    private final Path database;

    record PendingKnowledgeChunk(String outboxId, String chunkId, String articleId, String courseId,
                                 String visibility, int revision, int chunkIndex, String content,
                                 String contentHash, int attempts) {}

    record VectorCandidate(String chunkId, double score) {}

    V14CloudStore(Path database) throws SQLException {
        this.database = Objects.requireNonNull(database, "database must not be null").toAbsolutePath().normalize();
        initialize();
    }

    CourseCatalog createCourse(AuthenticatedUser actor, String name, String description) {
        requireTeacherRole(actor);
        String normalizedName = required(name, "name", 120);
        String normalizedDescription = optional(description, 2_000);
        Instant now = Instant.now();
        String id = UUID.randomUUID().toString();
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
            "insert into courses(id,name,description,status,version,created_by,created_at,updated_at) values(?,?,?,?,?,?,?,?)")) {
            statement.setString(1, id);
            statement.setString(2, normalizedName);
            statement.setString(3, normalizedDescription);
            statement.setString(4, ContentStatus.ACTIVE.name());
            statement.setLong(5, 1);
            statement.setString(6, actor.id());
            statement.setString(7, now.toString());
            statement.setString(8, now.toString());
            statement.executeUpdate();
            return new CourseCatalog(id, normalizedName, normalizedDescription, ContentStatus.ACTIVE, 1,
                actor.id(), now, now);
        } catch (SQLException error) {
            throw database(error);
        }
    }

    List<CourseCatalog> listCourses(AuthenticatedUser actor) {
        requireTeacherRole(actor);
        String sql = actor.hasRole(UserRole.ADMIN)
            ? "select * from courses order by updated_at desc,id"
            : "select * from courses where created_by=? or status='ACTIVE' order by updated_at desc,id";
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            if (!actor.hasRole(UserRole.ADMIN)) statement.setString(1, actor.id());
            List<CourseCatalog> result = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(course(rows));
            }
            return List.copyOf(result);
        } catch (SQLException error) {
            throw database(error);
        }
    }

    CourseCatalog updateCourse(AuthenticatedUser actor, String courseId, String name, String description,
                               ContentStatus status, long expectedVersion) {
        requireCourseOwner(actor, courseId);
        if (expectedVersion < 1) throw new IllegalArgumentException("expectedVersion must be positive");
        String normalizedName = required(name, "name", 120);
        String normalizedDescription = optional(description, 2_000);
        ContentStatus normalizedStatus = Objects.requireNonNull(status, "status must not be null");
        Instant now = Instant.now();
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
            "update courses set name=?,description=?,status=?,version=version+1,updated_at=? where id=? and version=?")) {
            statement.setString(1, normalizedName);
            statement.setString(2, normalizedDescription);
            statement.setString(3, normalizedStatus.name());
            statement.setString(4, now.toString());
            statement.setString(5, courseId);
            statement.setLong(6, expectedVersion);
            if (statement.executeUpdate() != 1) throw new V14VersionConflictException("Course version is stale");
            return findCourse(connection, courseId);
        } catch (SQLException error) {
            throw database(error);
        }
    }

    CourseSection createSection(AuthenticatedUser actor, String courseId, String name, int sortOrder) {
        requireCourseOwner(actor, courseId);
        if (sortOrder < 0) throw new IllegalArgumentException("sortOrder must not be negative");
        String id = UUID.randomUUID().toString();
        String normalizedName = required(name, "name", 120);
        Instant now = Instant.now();
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
            "insert into course_sections(id,course_id,name,sort_order,status,version,created_at,updated_at) values(?,?,?,?,?,?,?,?)")) {
            statement.setString(1, id);
            statement.setString(2, courseId);
            statement.setString(3, normalizedName);
            statement.setInt(4, sortOrder);
            statement.setString(5, ContentStatus.ACTIVE.name());
            statement.setLong(6, 1);
            statement.setString(7, now.toString());
            statement.setString(8, now.toString());
            statement.executeUpdate();
            return new CourseSection(id, courseId, normalizedName, sortOrder, ContentStatus.ACTIVE, 1, now, now);
        } catch (SQLException error) {
            throw database(error);
        }
    }

    KnowledgePoint createKnowledgePoint(AuthenticatedUser actor, String courseId, String sectionId, String name,
                                        String description, int sortOrder) {
        requireCourseOwner(actor, courseId);
        if (sortOrder < 0) throw new IllegalArgumentException("sortOrder must not be negative");
        try (Connection connection = open()) {
            if (sectionId != null && !sectionId.isBlank()) requireSection(connection, courseId, sectionId);
            String id = UUID.randomUUID().toString();
            Instant now = Instant.now();
            try (PreparedStatement statement = connection.prepareStatement(
                "insert into knowledge_points(id,course_id,section_id,name,description,sort_order,status,version,created_at,updated_at) values(?,?,?,?,?,?,?,?,?,?)")) {
                statement.setString(1, id);
                statement.setString(2, courseId);
                statement.setString(3, blankToNull(sectionId));
                statement.setString(4, required(name, "name", 120));
                statement.setString(5, optional(description, 1_000));
                statement.setInt(6, sortOrder);
                statement.setString(7, ContentStatus.ACTIVE.name());
                statement.setLong(8, 1);
                statement.setString(9, now.toString());
                statement.setString(10, now.toString());
                statement.executeUpdate();
            }
            return findKnowledgePoint(connection, id);
        } catch (SQLException error) {
            throw database(error);
        }
    }

    List<CourseSection> listSections(AuthenticatedUser actor, String courseId) {
        requireCourseVisible(actor, courseId);
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
            "select * from course_sections where course_id=? order by sort_order,id")) {
            statement.setString(1, courseId);
            List<CourseSection> result = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(section(rows));
            }
            return List.copyOf(result);
        } catch (SQLException error) {
            throw database(error);
        }
    }

    CourseSection updateSection(AuthenticatedUser actor, String courseId, String sectionId, String name,
                                int sortOrder, ContentStatus status, long expectedVersion) {
        requireCourseOwner(actor, courseId);
        if (sortOrder < 0 || expectedVersion < 1) throw new IllegalArgumentException("Section version or order is invalid");
        Instant now = Instant.now();
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
            "update course_sections set name=?,sort_order=?,status=?,version=version+1,updated_at=? "
                + "where id=? and course_id=? and version=?")) {
            statement.setString(1, required(name, "name", 120));
            statement.setInt(2, sortOrder);
            statement.setString(3, Objects.requireNonNull(status).name());
            statement.setString(4, now.toString());
            statement.setString(5, sectionId);
            statement.setString(6, courseId);
            statement.setLong(7, expectedVersion);
            if (statement.executeUpdate() != 1) throw new V14VersionConflictException("Section version is stale");
            try (PreparedStatement select = connection.prepareStatement("select * from course_sections where id=?")) {
                select.setString(1, sectionId);
                try (ResultSet row = select.executeQuery()) {
                    row.next();
                    return section(row);
                }
            }
        } catch (SQLException error) {
            throw database(error);
        }
    }

    List<KnowledgePoint> listKnowledgePoints(AuthenticatedUser actor, String courseId) {
        requireCourseVisible(actor, courseId);
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
            "select * from knowledge_points where course_id=? order by sort_order,id")) {
            statement.setString(1, courseId);
            List<KnowledgePoint> result = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(knowledgePoint(rows));
            }
            return List.copyOf(result);
        } catch (SQLException error) {
            throw database(error);
        }
    }

    KnowledgePoint updateKnowledgePoint(AuthenticatedUser actor, String courseId, String knowledgePointId,
                                        String sectionId, String name, String description, int sortOrder,
                                        ContentStatus status, long expectedVersion) {
        requireCourseOwner(actor, courseId);
        if (sortOrder < 0 || expectedVersion < 1) throw new IllegalArgumentException("Knowledge point version or order is invalid");
        try (Connection connection = open()) {
            if (sectionId != null && !sectionId.isBlank()) requireSection(connection, courseId, sectionId);
            Instant now = Instant.now();
            try (PreparedStatement statement = connection.prepareStatement(
                "update knowledge_points set section_id=?,name=?,description=?,sort_order=?,status=?,"
                    + "version=version+1,updated_at=? where id=? and course_id=? and version=?")) {
                statement.setString(1, blankToNull(sectionId));
                statement.setString(2, required(name, "name", 120));
                statement.setString(3, optional(description, 1_000));
                statement.setInt(4, sortOrder);
                statement.setString(5, Objects.requireNonNull(status).name());
                statement.setString(6, now.toString());
                statement.setString(7, knowledgePointId);
                statement.setString(8, courseId);
                statement.setLong(9, expectedVersion);
                if (statement.executeUpdate() != 1) throw new V14VersionConflictException("Knowledge point version is stale");
            }
            return findKnowledgePoint(connection, knowledgePointId);
        } catch (SQLException error) {
            throw database(error);
        }
    }

    SharedExerciseVersion publishExercise(AuthenticatedUser actor, String courseId, String exerciseId, String title,
                                          String prompt, String datasetVersion, String evaluationRule,
                                          List<String> knowledgePointIds, String operationId) {
        requireCourseOwner(actor, courseId);
        String op = requiredOperation(operationId);
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                String prior = operationResource(connection, actor.id(), op, "EXERCISE_VERSION");
                if (prior != null) {
                    SharedExerciseVersion existing = findExerciseVersion(connection, prior);
                    connection.rollback();
                    return existing;
                }
                String logicalId = blankToNull(exerciseId);
                if (logicalId == null) logicalId = UUID.randomUUID().toString();
                requireKnowledgePoints(connection, courseId, knowledgePointIds);
                int version = 1;
                try (PreparedStatement select = connection.prepareStatement(
                    "select course_id,current_version from shared_exercises where id=?")) {
                    select.setString(1, logicalId);
                    try (ResultSet row = select.executeQuery()) {
                        if (row.next()) {
                            if (!courseId.equals(row.getString(1))) throw new SecurityException("exercise belongs to another course");
                            version = row.getInt(2) + 1;
                        }
                    }
                }
                Instant now = Instant.now();
                try (PreparedStatement upsert = connection.prepareStatement(
                    "insert into shared_exercises(id,course_id,current_version,status,created_by,created_at,updated_at) "
                        + "values(?,?,?,?,?,?,?) on conflict(id) do update set current_version=excluded.current_version,"
                        + "status='ACTIVE',updated_at=excluded.updated_at")) {
                    upsert.setString(1, logicalId);
                    upsert.setString(2, courseId);
                    upsert.setInt(3, version);
                    upsert.setString(4, ContentStatus.ACTIVE.name());
                    upsert.setString(5, actor.id());
                    upsert.setString(6, now.toString());
                    upsert.setString(7, now.toString());
                    upsert.executeUpdate();
                }
                String versionId = UUID.randomUUID().toString();
                String kpJson = writeJson(normalizeIds(knowledgePointIds));
                String normalizedTitle = required(title, "title", 160);
                String normalizedPrompt = required(prompt, "prompt", 8_000);
                String normalizedDataset = required(datasetVersion, "datasetVersion", 160);
                String normalizedRule = required(evaluationRule, "evaluationRule", 4_000);
                String hash = sha256(String.join("\n", logicalId, Integer.toString(version), normalizedTitle,
                    normalizedPrompt, normalizedDataset, normalizedRule, kpJson));
                try (PreparedStatement insert = connection.prepareStatement(
                    "insert into shared_exercise_versions(id,exercise_id,course_id,version,title,prompt,dataset_version,"
                        + "evaluation_rule,knowledge_point_ids_json,content_hash,created_by,published_at) values(?,?,?,?,?,?,?,?,?,?,?,?)")) {
                    insert.setString(1, versionId);
                    insert.setString(2, logicalId);
                    insert.setString(3, courseId);
                    insert.setInt(4, version);
                    insert.setString(5, normalizedTitle);
                    insert.setString(6, normalizedPrompt);
                    insert.setString(7, normalizedDataset);
                    insert.setString(8, normalizedRule);
                    insert.setString(9, kpJson);
                    insert.setString(10, hash);
                    insert.setString(11, actor.id());
                    insert.setString(12, now.toString());
                    insert.executeUpdate();
                }
                recordOperation(connection, actor.id(), op, "EXERCISE_VERSION", versionId);
                connection.commit();
                return new SharedExerciseVersion(versionId, logicalId, courseId, version, normalizedTitle,
                    normalizedPrompt, normalizedDataset, normalizedRule, normalizeIds(knowledgePointIds), hash,
                    ContentStatus.ACTIVE, actor.id(), now);
            } catch (RuntimeException | SQLException error) {
                connection.rollback();
                if (error instanceof RuntimeException runtime) throw runtime;
                throw error;
            }
        } catch (SQLException error) {
            throw database(error);
        }
    }

    List<SharedExerciseVersion> listExercises(AuthenticatedUser actor, String courseId, String knowledgePointId) {
        requireCourseVisible(actor, courseId);
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
            "select v.*,e.status from shared_exercise_versions v join shared_exercises e on e.id=v.exercise_id "
                + "where v.course_id=? order by v.published_at desc,v.id")) {
            statement.setString(1, courseId);
            List<SharedExerciseVersion> result = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    SharedExerciseVersion version = exerciseVersion(rows);
                    if (knowledgePointId == null || knowledgePointId.isBlank()
                        || version.knowledgePointIds().contains(knowledgePointId)) result.add(version);
                }
            }
            return List.copyOf(result);
        } catch (SQLException error) {
            throw database(error);
        }
    }

    SharedExerciseVersion setExerciseStatus(AuthenticatedUser actor, String courseId, String exerciseId,
                                            ContentStatus status) {
        requireCourseOwner(actor, courseId);
        try (Connection connection = open(); PreparedStatement update = connection.prepareStatement(
            "update shared_exercises set status=?,updated_at=? where id=? and course_id=?")) {
            update.setString(1, Objects.requireNonNull(status).name());
            update.setString(2, Instant.now().toString());
            update.setString(3, exerciseId);
            update.setString(4, courseId);
            if (update.executeUpdate() != 1) throw new IllegalArgumentException("Shared exercise not found");
            try (PreparedStatement select = connection.prepareStatement(
                "select v.*,e.status from shared_exercise_versions v join shared_exercises e on e.id=v.exercise_id "
                    + "where e.id=? and v.version=e.current_version")) {
                select.setString(1, exerciseId);
                try (ResultSet row = select.executeQuery()) {
                    row.next();
                    return exerciseVersion(row);
                }
            }
        } catch (SQLException error) {
            throw database(error);
        }
    }

    ClassAssignment createAssignmentFromVersion(AuthenticatedUser actor, String classroomId, String versionId,
                                                String title, String description, Instant dueAt, String operationId) {
        requireClassTeacher(actor, classroomId);
        if (dueAt != null && !dueAt.isAfter(Instant.now())) throw new IllegalArgumentException("dueAt must be in the future");
        String op = requiredOperation(operationId);
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                String prior = operationResource(connection, actor.id(), op, "ASSIGNMENT");
                if (prior != null) {
                    ClassAssignment existing = assignment(connection, classroomId, prior);
                    connection.rollback();
                    return existing;
                }
                SharedExerciseVersion exercise = findExerciseVersion(connection, versionId);
                if (exercise.status() != ContentStatus.ACTIVE) {
                    throw new IllegalArgumentException("Inactive exercise versions cannot create new assignments");
                }
                CourseCatalog course = findCourse(connection, exercise.courseId());
                if (course.status() != ContentStatus.ACTIVE) {
                    throw new IllegalArgumentException("Inactive courses cannot create new assignments");
                }
                String assignmentId = UUID.randomUUID().toString();
                Instant now = Instant.now();
                String normalizedTitle = title == null || title.isBlank() ? exercise.title() : required(title, "title", 160);
                String normalizedDescription = optional(description, 2_000);
                try (PreparedStatement statement = connection.prepareStatement(
                    "insert into class_assignments(id,classroom_id,exercise_id,title,description,created_at,status,due_at,"
                        + "published_at,version,updated_at) values(?,?,?,?,?,?,?,?,?,?,?)")) {
                    statement.setString(1, assignmentId);
                    statement.setString(2, classroomId);
                    statement.setString(3, exercise.exerciseId());
                    statement.setString(4, normalizedTitle);
                    statement.setString(5, normalizedDescription);
                    statement.setString(6, now.toString());
                    statement.setString(7, AssignmentStatus.PUBLISHED.name());
                    statement.setString(8, dueAt == null ? null : dueAt.toString());
                    statement.setString(9, now.toString());
                    statement.setLong(10, 1);
                    statement.setString(11, now.toString());
                    statement.executeUpdate();
                }
                String kpJson = writeJson(exercise.knowledgePointIds());
                String snapshotHash = sha256(String.join("\n", versionId, exercise.contentHash(), normalizedTitle));
                try (PreparedStatement snapshot = connection.prepareStatement(
                    "insert into assignment_content_snapshots(assignment_id,exercise_version_id,title,prompt,dataset_version,"
                        + "evaluation_rule,knowledge_point_ids_json,snapshot_hash,created_at) values(?,?,?,?,?,?,?,?,?)")) {
                    snapshot.setString(1, assignmentId);
                    snapshot.setString(2, versionId);
                    snapshot.setString(3, exercise.title());
                    snapshot.setString(4, exercise.prompt());
                    snapshot.setString(5, exercise.datasetVersion());
                    snapshot.setString(6, exercise.evaluationRule());
                    snapshot.setString(7, kpJson);
                    snapshot.setString(8, snapshotHash);
                    snapshot.setString(9, now.toString());
                    snapshot.executeUpdate();
                }
                notifyClassStudents(connection, classroomId, NotificationType.ASSIGNMENT_PUBLISHED,
                    "ASSIGNMENT", assignmentId, "新任务：" + normalizedTitle, "教师发布了新的 SQL 任务。",
                    "assignment-published:" + assignmentId);
                recordOperation(connection, actor.id(), op, "ASSIGNMENT", assignmentId);
                connection.commit();
                return new ClassAssignment(assignmentId, classroomId, exercise.exerciseId(), normalizedTitle, now,
                    AssignmentStatus.PUBLISHED, dueAt, now, normalizedDescription, now, null, 1);
            } catch (RuntimeException | SQLException error) {
                connection.rollback();
                if (error instanceof RuntimeException runtime) throw runtime;
                throw error;
            }
        } catch (SQLException error) {
            throw database(error);
        }
    }

    AssignmentContentSnapshot assignmentSnapshot(AuthenticatedUser actor, String classroomId, String assignmentId) {
        requireClassMember(actor, classroomId);
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
            "select snap.* from assignment_content_snapshots snap join class_assignments a on a.id=snap.assignment_id "
                + "where snap.assignment_id=? and a.classroom_id=?")) {
            statement.setString(1, assignmentId);
            statement.setString(2, classroomId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new IllegalArgumentException("Assignment snapshot was not found");
                return snapshot(row);
            }
        } catch (SQLException error) {
            throw database(error);
        }
    }

    void recordSubmissionNotification(AuthenticatedUser actor, String classroomId, AssignmentSubmission submission) {
        try (Connection connection = open()) {
            notifyUser(connection, actor.id(), NotificationType.SUBMISSION_CONFIRMED, "ASSIGNMENT",
                submission.assignmentId(), "提交已确认", "第 " + submission.attemptNumber() + " 次提交已由服务端确认。",
                "submission-confirmed:" + submission.id());
        } catch (SQLException error) {
            throw database(error);
        }
    }

    SubmissionFeedback saveFeedback(AuthenticatedUser actor, String classroomId, String assignmentId,
                                    String submissionId, FeedbackStatus status, String comment,
                                    List<String> knowledgePointIds, long expectedVersion, String operationId) {
        requireClassTeacher(actor, classroomId);
        Objects.requireNonNull(status, "status must not be null");
        String op = requiredOperation(operationId);
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                String prior = operationResource(connection, actor.id(), op, "FEEDBACK");
                if (prior != null) {
                    SubmissionFeedback existing = feedback(connection, prior);
                    connection.rollback();
                    return existing;
                }
                SubmissionIdentity submission = submissionIdentity(connection, classroomId, assignmentId, submissionId);
                AssignmentContentSnapshot snapshot = findSnapshot(connection, assignmentId);
                List<String> ids = normalizeIds(knowledgePointIds);
                if (!snapshot.knowledgePointIds().containsAll(ids)) {
                    throw new IllegalArgumentException("Feedback knowledge points must belong to the assignment snapshot");
                }
                String normalizedComment = optional(comment, 2_000);
                Instant now = Instant.now();
                long currentVersion = feedbackVersion(connection, submissionId);
                if (currentVersion != expectedVersion) throw new V14VersionConflictException("Feedback version is stale");
                if (currentVersion == 0) {
                    try (PreparedStatement insert = connection.prepareStatement(
                        "insert into submission_feedback(submission_id,assignment_id,student_user_id,status,comment,"
                            + "knowledge_point_ids_json,version,author_user_id,updated_at) values(?,?,?,?,?,?,?,?,?)")) {
                        bindFeedback(insert, submissionId, assignmentId, submission.studentUserId(), status,
                            normalizedComment, ids, 1, actor.id(), now);
                        insert.executeUpdate();
                    }
                } else {
                    try (PreparedStatement update = connection.prepareStatement(
                        "update submission_feedback set status=?,comment=?,knowledge_point_ids_json=?,version=version+1,"
                            + "author_user_id=?,updated_at=? where submission_id=? and version=?")) {
                        update.setString(1, status.name());
                        update.setString(2, normalizedComment);
                        update.setString(3, writeJson(ids));
                        update.setString(4, actor.id());
                        update.setString(5, now.toString());
                        update.setString(6, submissionId);
                        update.setLong(7, expectedVersion);
                        if (update.executeUpdate() != 1) throw new V14VersionConflictException("Feedback version is stale");
                    }
                }
                long nextVersion = currentVersion + 1;
                try (PreparedStatement audit = connection.prepareStatement(
                    "insert into feedback_audit(id,submission_id,actor_user_id,status,version,created_at) values(?,?,?,?,?,?)")) {
                    audit.setString(1, UUID.randomUUID().toString());
                    audit.setString(2, submissionId);
                    audit.setString(3, actor.id());
                    audit.setString(4, status.name());
                    audit.setLong(5, nextVersion);
                    audit.setString(6, now.toString());
                    audit.executeUpdate();
                }
                notifyUser(connection, submission.studentUserId(), NotificationType.FEEDBACK_PUBLISHED, "ASSIGNMENT",
                    assignmentId, "收到教师反馈", "教师已更新你的任务反馈。", "feedback:" + submissionId + ":" + nextVersion);
                recordOperation(connection, actor.id(), op, "FEEDBACK", submissionId);
                connection.commit();
                return new SubmissionFeedback(submissionId, assignmentId, submission.studentUserId(), status,
                    normalizedComment, ids, nextVersion, actor.id(), now);
            } catch (RuntimeException | SQLException error) {
                connection.rollback();
                if (error instanceof RuntimeException runtime) throw runtime;
                throw error;
            }
        } catch (SQLException error) {
            throw database(error);
        }
    }

    List<SubmissionFeedback> listFeedback(AuthenticatedUser actor, String classroomId, String assignmentId) {
        boolean teacher = isClassTeacher(actor, classroomId);
        if (!teacher) requireClassStudent(actor, classroomId);
        String sql = "select f.* from submission_feedback f join assignment_submissions s on s.id=f.submission_id "
            + "where f.assignment_id=? and s.classroom_id=?" + (teacher ? "" : " and s.user_id=?")
            + " order by f.updated_at desc";
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, assignmentId);
            statement.setString(2, classroomId);
            if (!teacher) statement.setString(3, actor.id());
            List<SubmissionFeedback> result = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(feedback(rows));
            }
            return List.copyOf(result);
        } catch (SQLException error) {
            throw database(error);
        }
    }

    FeedbackDraft draftFeedback(AuthenticatedUser actor, String classroomId, String assignmentId, String submissionId) {
        requireClassTeacher(actor, classroomId);
        try (Connection connection = open()) {
            SubmissionIdentity submission = submissionIdentity(connection, classroomId, assignmentId, submissionId);
            AssignmentContentSnapshot snapshot = findSnapshot(connection, assignmentId);
            String outcome = submission.passed() ? "本次提交已通过确定性评测。" : "本次提交尚未通过确定性评测。";
            String error = submission.errorCode() == null || submission.errorCode().isBlank()
                ? "请结合评测项逐步核对查询结果。" : "优先检查错误类型：" + submission.errorCode() + "。";
            List<String> evidence = new ArrayList<>();
            evidence.add("确定性结果：" + (submission.passed() ? "PASSED" : "FAILED"));
            if (submission.errorCode() != null && !submission.errorCode().isBlank()) {
                evidence.add("错误码：" + submission.errorCode());
            }
            evidence.add("内容快照：" + snapshot.snapshotHash());
            return new FeedbackDraft(outcome + error + " 建议先复习关联知识点，再重新运行并提交。", evidence, false);
        } catch (SQLException error) {
            throw database(error);
        }
    }

    List<KnowledgeMastery> mastery(AuthenticatedUser actor, String classroomId, String requestedStudentId) {
        String studentId;
        if (isClassTeacher(actor, classroomId)) {
            studentId = required(requestedStudentId, "studentUserId", 64);
            requireStudentId(classroomId, studentId);
        } else {
            requireClassStudent(actor, classroomId);
            studentId = actor.id();
        }
        Map<String, MutableMastery> totals = new LinkedHashMap<>();
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
            "select s.status,snap.knowledge_point_ids_json from assignment_submissions s "
                + "join assignment_content_snapshots snap on snap.assignment_id=s.assignment_id "
                + "where s.classroom_id=? and s.user_id=? order by s.submitted_at,s.id")) {
            statement.setString(1, classroomId);
            statement.setString(2, studentId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    boolean passed = "PASSED".equals(rows.getString(1));
                    for (String id : readIds(rows.getString(2))) {
                        MutableMastery value = totals.computeIfAbsent(id, ignored -> new MutableMastery());
                        value.attempts++;
                        if (passed) value.passes++;
                    }
                }
            }
            List<KnowledgeMastery> result = new ArrayList<>();
            for (Map.Entry<String, MutableMastery> entry : totals.entrySet()) {
                KnowledgePoint point = findKnowledgePoint(connection, entry.getKey());
                MutableMastery value = entry.getValue();
                int percent = value.attempts == 0 ? 0 : (int) Math.round(value.passes * 100.0 / value.attempts);
                result.add(new KnowledgeMastery(point.id(), point.name(), value.attempts, value.passes, percent,
                    recommendations(connection, point.id(), percent)));
            }
            result.sort(Comparator.comparingInt(KnowledgeMastery::masteryPercent)
                .thenComparing(KnowledgeMastery::knowledgePointId));
            return List.copyOf(result);
        } catch (SQLException error) {
            throw database(error);
        }
    }

    List<CloudNotification> notifications(AuthenticatedUser actor, int page, int pageSize) {
        if (page < 0 || pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("Notification page is invalid");
        }
        createDueNotifications(actor);
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
            "select * from cloud_notifications where recipient_user_id=? order by created_at desc,id limit ? offset ?")) {
            statement.setString(1, actor.id());
            statement.setInt(2, pageSize);
            statement.setInt(3, page * pageSize);
            List<CloudNotification> result = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    CloudNotification item = notification(rows);
                    if (notificationResourceVisible(connection, actor, item)) result.add(item);
                }
            }
            return List.copyOf(result);
        } catch (SQLException error) {
            throw database(error);
        }
    }

    private boolean notificationResourceVisible(Connection connection, AuthenticatedUser actor,
                                                CloudNotification notification) throws SQLException {
        if (!"ASSIGNMENT".equals(notification.resourceType())) return false;
        try (PreparedStatement statement = connection.prepareStatement(
            "select 1 from class_assignments a left join classroom_members m on m.classroom_id=a.classroom_id "
                + "and m.user_id=? where a.id=? and (m.user_id is not null or ?=1)")) {
            statement.setString(1, actor.id());
            statement.setString(2, notification.resourceId());
            statement.setInt(3, actor.hasRole(UserRole.ADMIN) ? 1 : 0);
            try (ResultSet row = statement.executeQuery()) {
                return row.next();
            }
        }
    }

    CloudNotification markRead(AuthenticatedUser actor, String notificationId) {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
            "update cloud_notifications set read_at=coalesce(read_at,?) where id=? and recipient_user_id=?")) {
            statement.setString(1, Instant.now().toString());
            statement.setString(2, notificationId);
            statement.setString(3, actor.id());
            if (statement.executeUpdate() != 1) throw new SecurityException("notification is not visible");
            try (PreparedStatement select = connection.prepareStatement("select * from cloud_notifications where id=?")) {
                select.setString(1, notificationId);
                try (ResultSet row = select.executeQuery()) {
                    row.next();
                    return notification(row);
                }
            }
        } catch (SQLException error) {
            throw database(error);
        }
    }

    String exportCourse(AuthenticatedUser actor, String courseId) {
        requireCourseVisible(actor, courseId);
        try (Connection connection = open()) {
            Map<String, Object> bundle = new LinkedHashMap<>();
            bundle.put("formatVersion", 1);
            bundle.put("course", findCourse(connection, courseId));
            bundle.put("sections", listSections(actor, courseId));
            bundle.put("knowledgePoints", listKnowledgePoints(actor, courseId));
            bundle.put("exercises", listExercises(actor, courseId, null));
            return writeJson(bundle);
        } catch (SQLException error) {
            throw database(error);
        }
    }

    CourseBundleImportResult importCourse(AuthenticatedUser actor, String bundleJson, String operationId) {
        requireTeacherRole(actor);
        String op = requiredOperation(operationId);
        if (bundleJson == null || bundleJson.length() > 2_000_000) throw new IllegalArgumentException("Course bundle is invalid");
        Map<String, Object> bundle;
        try {
            bundle = JSON.readValue(bundleJson, new TypeReference<>() { });
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("Course bundle JSON is invalid", error);
        }
        if (!Integer.valueOf(1).equals(bundle.get("formatVersion"))) {
            throw new IllegalArgumentException("Unsupported course bundle format");
        }
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                String existingCourseId = operationResource(connection, actor.id(), op, "COURSE_IMPORT");
                if (existingCourseId != null) {
                    CourseBundleImportResult existing = importResult(connection, existingCourseId);
                    connection.rollback();
                    return existing;
                }
            Map<String, Object> courseData = objectMap(bundle.get("course"));
                String courseId = UUID.randomUUID().toString();
                Instant now = Instant.now();
                try (PreparedStatement insert = connection.prepareStatement(
                    "insert into courses(id,name,description,status,version,created_by,created_at,updated_at) values(?,?,?,?,?,?,?,?)")) {
                    insert.setString(1, courseId);
                    insert.setString(2, required(String.valueOf(courseData.get("name")), "name", 120));
                    insert.setString(3, optional(String.valueOf(courseData.getOrDefault("description", "")), 2_000));
                    insert.setString(4, ContentStatus.ACTIVE.name());
                    insert.setLong(5, 1);
                    insert.setString(6, actor.id());
                    insert.setString(7, now.toString());
                    insert.setString(8, now.toString());
                    insert.executeUpdate();
                }
            Map<String, String> sectionIds = new LinkedHashMap<>();
            int sectionCount = 0;
            for (Object item : objectList(bundle.get("sections"))) {
                Map<String, Object> section = objectMap(item);
                    String sectionId = UUID.randomUUID().toString();
                    int sectionOrder = intValue(section.get("sortOrder"));
                    if (sectionOrder < 0) throw new IllegalArgumentException("Section sort order is invalid");
                    try (PreparedStatement insert = connection.prepareStatement(
                        "insert into course_sections(id,course_id,name,sort_order,status,version,created_at,updated_at) values(?,?,?,?,?,?,?,?)")) {
                        insert.setString(1, sectionId);
                        insert.setString(2, courseId);
                        insert.setString(3, required(String.valueOf(section.get("name")), "section name", 120));
                        insert.setInt(4, sectionOrder);
                        insert.setString(5, ContentStatus.ACTIVE.name());
                        insert.setLong(6, 1);
                        insert.setString(7, now.toString());
                        insert.setString(8, now.toString());
                        insert.executeUpdate();
                    }
                    sectionIds.put(String.valueOf(section.get("id")), sectionId);
                sectionCount++;
            }
            Map<String, String> pointIds = new LinkedHashMap<>();
            int pointCount = 0;
            for (Object item : objectList(bundle.get("knowledgePoints"))) {
                Map<String, Object> point = objectMap(item);
                String oldSection = point.get("sectionId") == null ? null : String.valueOf(point.get("sectionId"));
                    String pointId = UUID.randomUUID().toString();
                    int pointOrder = intValue(point.get("sortOrder"));
                    if (pointOrder < 0) throw new IllegalArgumentException("Knowledge point sort order is invalid");
                    try (PreparedStatement insert = connection.prepareStatement(
                        "insert into knowledge_points(id,course_id,section_id,name,description,sort_order,status,version,created_at,updated_at) "
                            + "values(?,?,?,?,?,?,?,?,?,?)")) {
                        insert.setString(1, pointId);
                        insert.setString(2, courseId);
                        insert.setString(3, sectionIds.get(oldSection));
                        insert.setString(4, required(String.valueOf(point.get("name")), "knowledge point name", 120));
                        insert.setString(5, optional(String.valueOf(point.getOrDefault("description", "")), 1_000));
                        insert.setInt(6, pointOrder);
                        insert.setString(7, ContentStatus.ACTIVE.name());
                        insert.setLong(8, 1);
                        insert.setString(9, now.toString());
                        insert.setString(10, now.toString());
                        insert.executeUpdate();
                    }
                    pointIds.put(String.valueOf(point.get("id")), pointId);
                pointCount++;
            }
            int exerciseCount = 0;
            Set<String> importedLogicalExercises = new LinkedHashSet<>();
            for (Object item : objectList(bundle.get("exercises"))) {
                Map<String, Object> exercise = objectMap(item);
                String oldLogicalId = String.valueOf(exercise.get("exerciseId"));
                if (!importedLogicalExercises.add(oldLogicalId)) continue;
                List<String> mappedPoints = stringList(exercise.get("knowledgePointIds")).stream()
                    .map(pointIds::get).filter(Objects::nonNull).toList();
                    String logicalId = UUID.randomUUID().toString();
                    String versionId = UUID.randomUUID().toString();
                    String title = required(String.valueOf(exercise.get("title")), "title", 160);
                    String prompt = required(String.valueOf(exercise.get("prompt")), "prompt", 8_000);
                    String dataset = required(String.valueOf(exercise.get("datasetVersion")), "datasetVersion", 160);
                    String rule = required(String.valueOf(exercise.get("evaluationRule")), "evaluationRule", 4_000);
                    String kpJson = writeJson(mappedPoints);
                    String hash = sha256(String.join("\n", logicalId, "1", title, prompt, dataset, rule, kpJson));
                    try (PreparedStatement logical = connection.prepareStatement(
                        "insert into shared_exercises(id,course_id,current_version,status,created_by,created_at,updated_at) values(?,?,?,?,?,?,?)");
                         PreparedStatement version = connection.prepareStatement(
                        "insert into shared_exercise_versions(id,exercise_id,course_id,version,title,prompt,dataset_version,"
                            + "evaluation_rule,knowledge_point_ids_json,content_hash,created_by,published_at) values(?,?,?,?,?,?,?,?,?,?,?,?)")) {
                        logical.setString(1, logicalId);
                        logical.setString(2, courseId);
                        logical.setInt(3, 1);
                        logical.setString(4, ContentStatus.ACTIVE.name());
                        logical.setString(5, actor.id());
                        logical.setString(6, now.toString());
                        logical.setString(7, now.toString());
                        logical.executeUpdate();
                        version.setString(1, versionId);
                        version.setString(2, logicalId);
                        version.setString(3, courseId);
                        version.setInt(4, 1);
                        version.setString(5, title);
                        version.setString(6, prompt);
                        version.setString(7, dataset);
                        version.setString(8, rule);
                        version.setString(9, kpJson);
                        version.setString(10, hash);
                        version.setString(11, actor.id());
                        version.setString(12, now.toString());
                        version.executeUpdate();
                    }
                exerciseCount++;
            }
                recordOperation(connection, actor.id(), op, "COURSE_IMPORT", courseId);
                connection.commit();
                return new CourseBundleImportResult(courseId, sectionCount, pointCount, exerciseCount);
            } catch (RuntimeException | SQLException error) {
                connection.rollback();
                if (error instanceof RuntimeException runtime) throw runtime;
                throw error;
            }
        } catch (SQLException error) {
            throw database(error);
        }
    }

    private CourseBundleImportResult importResult(Connection connection, String courseId) throws SQLException {
        int sections = count(connection, "course_sections", courseId);
        int points = count(connection, "knowledge_points", courseId);
        int exercises = count(connection, "shared_exercises", courseId);
        return new CourseBundleImportResult(courseId, sections, points, exercises);
    }

    private int count(Connection connection, String table, String courseId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "select count(*) from " + table + " where course_id=?")) {
            statement.setString(1, courseId);
            try (ResultSet row = statement.executeQuery()) {
                return row.getInt(1);
            }
        }
    }

    private void createDueNotifications(AuthenticatedUser actor) {
        if (!actor.hasRole(UserRole.STUDENT)) return;
        Instant now = Instant.now();
        Instant horizon = now.plus(24, ChronoUnit.HOURS);
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
            "select a.id,a.title from class_assignments a join classroom_members m on m.classroom_id=a.classroom_id "
                + "where m.user_id=? and m.role='STUDENT' and a.status='PUBLISHED' and a.due_at>? and a.due_at<=?")) {
            statement.setString(1, actor.id());
            statement.setString(2, now.toString());
            statement.setString(3, horizon.toString());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    notifyUser(connection, actor.id(), NotificationType.ASSIGNMENT_DUE, "ASSIGNMENT",
                        rows.getString(1), "任务即将截止：" + rows.getString(2), "任务将在 24 小时内截止。",
                        "assignment-due:" + rows.getString(1));
                }
            }
        } catch (SQLException error) {
            throw database(error);
        }
    }

    private List<ExerciseRecommendation> recommendations(Connection connection, String knowledgePointId,
                                                          int masteryPercent) throws SQLException {
        if (masteryPercent >= 80) return List.of();
        List<ExerciseRecommendation> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
            "select v.*,'ACTIVE' status from shared_exercise_versions v join shared_exercises e on e.id=v.exercise_id "
                + "where e.status='ACTIVE' and v.version=e.current_version order by v.title,v.id")) {
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next() && result.size() < 3) {
                    SharedExerciseVersion exercise = exerciseVersion(rows);
                    if (exercise.knowledgePointIds().contains(knowledgePointId)) {
                        result.add(new ExerciseRecommendation(exercise.id(), exercise.title(), knowledgePointId,
                            "该知识点当前掌握度为 " + masteryPercent + "%", result.size() + 1));
                    }
                }
            }
        }
        return List.copyOf(result);
    }

    private void notifyClassStudents(Connection connection, String classroomId, NotificationType type,
                                     String resourceType, String resourceId, String title, String message,
                                     String key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "select user_id from classroom_members where classroom_id=? and role='STUDENT'")) {
            statement.setString(1, classroomId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) notifyUser(connection, rows.getString(1), type, resourceType, resourceId,
                    title, message, key);
            }
        }
    }

    private void notifyUser(Connection connection, String userId, NotificationType type, String resourceType,
                            String resourceId, String title, String message, String key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "insert or ignore into cloud_notifications(id,recipient_user_id,type,resource_type,resource_id,title,message,"
                + "idempotency_key,created_at) values(?,?,?,?,?,?,?,?,?)")) {
            statement.setString(1, UUID.randomUUID().toString());
            statement.setString(2, userId);
            statement.setString(3, type.name());
            statement.setString(4, resourceType);
            statement.setString(5, resourceId);
            statement.setString(6, required(title, "title", 160));
            statement.setString(7, required(message, "message", 500));
            statement.setString(8, key);
            statement.setString(9, Instant.now().toString());
            statement.executeUpdate();
        }
    }

    CloudKnowledgeArticle publishKnowledge(AuthenticatedUser actor, String courseId, String sectionId,
                                           String title, String content, String visibility) {
        requireCourseOwner(actor, courseId);
        String normalizedTitle = required(title, "title", 200);
        String normalizedContent = required(content, "content", 2_000_000);
        String normalizedVisibility = required(visibility, "visibility", 20).toUpperCase(Locale.ROOT);
        if (!Set.of("PRIVATE", "PUBLISHED", "INACTIVE").contains(normalizedVisibility)) {
            throw new IllegalArgumentException("visibility is invalid");
        }
        String id = UUID.randomUUID().toString();
        String revisionId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        String hash = sha256(normalizedContent);
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try (PreparedStatement article = connection.prepareStatement("""
                     insert into cloud_knowledge_articles(id,course_id,section_id,title,visibility,current_revision,content_hash,created_by,created_at,updated_at)
                     values(?,?,?,?,?,1,?,?,?,?)
                     """);
                 PreparedStatement revision = connection.prepareStatement("""
                     insert into cloud_knowledge_revisions(id,article_id,revision,content,content_hash,created_by,created_at)
                     values(?,?,1,?,?,?,?)
                     """);
                 PreparedStatement chunk = connection.prepareStatement("""
                     insert into cloud_knowledge_chunks(id,article_id,revision_id,chunk_index,content,content_hash,index_status)
                     values(?,?,?,?,?,?,'PENDING')
                     """);
                 PreparedStatement outbox = connection.prepareStatement("""
                     insert into cloud_knowledge_index_outbox(id,chunk_id,operation,status,attempts,next_attempt_at,created_at,updated_at)
                     values(?,?,'UPSERT','PENDING',0,?,?,?)
                     """)) {
                article.setString(1, id); article.setString(2, courseId); article.setString(3, blankToNull(sectionId));
                article.setString(4, normalizedTitle); article.setString(5, normalizedVisibility); article.setString(6, hash);
                article.setString(7, actor.id()); article.setString(8, now.toString()); article.setString(9, now.toString());
                article.executeUpdate();
                revision.setString(1, revisionId); revision.setString(2, id); revision.setString(3, normalizedContent);
                revision.setString(4, hash); revision.setString(5, actor.id()); revision.setString(6, now.toString()); revision.executeUpdate();
                List<String> chunks = cloudChunks(normalizedContent);
                for (int index = 0; index < chunks.size(); index++) {
                    String value = chunks.get(index);
                    String chunkId = UUID.randomUUID().toString();
                    chunk.setString(1, chunkId); chunk.setString(2, id); chunk.setString(3, revisionId);
                    chunk.setInt(4, index); chunk.setString(5, value); chunk.setString(6, sha256(value)); chunk.addBatch();
                    outbox.setString(1, UUID.randomUUID().toString()); outbox.setString(2, chunkId);
                    outbox.setString(3, now.toString()); outbox.setString(4, now.toString());
                    outbox.setString(5, now.toString()); outbox.addBatch();
                }
                chunk.executeBatch();
                outbox.executeBatch();
                connection.commit();
                return new CloudKnowledgeArticle(id, courseId, sectionId, normalizedTitle, hash,
                    normalizedVisibility, 1, actor.id(), now);
            } catch (SQLException | RuntimeException error) { connection.rollback(); throw error; }
        } catch (SQLException error) { throw database(error); }
    }

    CoursePackagePreview previewCoursePackage(AuthenticatedUser actor, String packageJson) {
        requireTeacherRole(actor);
        SecureCoursePackage coursePackage = parseSecureCoursePackage(packageJson);
        Map<String, Object> payload;
        try {
            payload = JSON.readValue(coursePackage.payloadJson(), new TypeReference<>() { });
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("Course package payload JSON is invalid", error);
        }
        if (!Integer.valueOf(1).equals(payload.get("formatVersion"))) {
            throw new IllegalArgumentException("Unsupported course payload format");
        }
        Map<String, Object> course = objectMap(payload.get("course"));
        String title = required(String.valueOf(course.get("name")), "name", 120);
        List<?> sections = boundedItems(payload.get("sections"), 100, "sections");
        List<?> points = boundedItems(payload.get("knowledgePoints"), 1_000, "knowledgePoints");
        List<?> exercises = boundedItems(payload.get("exercises"), 2_000, "exercises");
        CoursePackagePreview.Conflict conflict = packageConflict(actor.id(), coursePackage);
        return new CoursePackagePreview(coursePackage.packageId(), title, coursePackage.courseVersion(),
            coursePackage.license(), coursePackage.contentSha256(), sections.size(), points.size(), exercises.size(),
            conflict);
    }

    CourseBundleImportResult importCoursePackage(AuthenticatedUser actor, String packageJson, String operationId,
                                                  String expectedSha256, boolean licenseConfirmed) {
        if (!licenseConfirmed) throw new SecurityException("Course package license confirmation is required");
        CoursePackagePreview preview = previewCoursePackage(actor, packageJson);
        String expected = required(expectedSha256, "expectedSha256", 64).toLowerCase(Locale.ROOT);
        if (!preview.contentSha256().equals(expected)) {
            throw new IllegalArgumentException("Course package changed after preview");
        }
        if (preview.conflict() == CoursePackagePreview.Conflict.VERSION_CONFLICT) {
            throw new IllegalStateException("Course package version conflicts with previously imported content");
        }
        SecureCoursePackage coursePackage = parseSecureCoursePackage(packageJson);
        if (preview.conflict() == CoursePackagePreview.Conflict.SAME_CONTENT) {
            return existingPackageImportResult(actor.id(), coursePackage);
        }
        CourseBundleImportResult result = importCourse(actor, coursePackage.payloadJson(), operationId);
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement("""
            insert or ignore into v20_course_package_operations(actor_user_id,operation_id,package_id,course_id,
                course_version,content_sha256,license,created_at) values(?,?,?,?,?,?,?,?)
            """)) {
            statement.setString(1, actor.id()); statement.setString(2, requiredOperation(operationId));
            statement.setString(3, coursePackage.packageId()); statement.setString(4, result.courseId());
            statement.setString(5, coursePackage.courseVersion()); statement.setString(6, coursePackage.contentSha256());
            statement.setString(7, coursePackage.license()); statement.setString(8, Instant.now().toString());
            statement.executeUpdate();
            return result;
        } catch (SQLException error) {
            throw database(error);
        }
    }

    private CourseBundleImportResult existingPackageImportResult(String actorId, SecureCoursePackage coursePackage) {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement("""
            select course_id from v20_course_package_operations
            where actor_user_id=? and package_id=? and course_version=? and content_sha256=?
            """)) {
            statement.setString(1, actorId); statement.setString(2, coursePackage.packageId());
            statement.setString(3, coursePackage.courseVersion()); statement.setString(4, coursePackage.contentSha256());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new IllegalStateException("Course package import audit is incomplete");
                return importResult(connection, row.getString(1));
            }
        } catch (SQLException error) {
            throw database(error);
        }
    }

    List<CloudArtifactSyncResult> uploadArtifactSync(AuthenticatedUser actor, List<CloudArtifactSyncItem> items) {
        if (items == null || items.size() > 200) throw new IllegalArgumentException("Artifact sync batch is too large");
        List<CloudArtifactSyncResult> results = new ArrayList<>();
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                for (CloudArtifactSyncItem item : items) results.add(storeArtifactSync(connection, actor.id(), item));
                connection.commit();
                return List.copyOf(results);
            } catch (RuntimeException | SQLException error) {
                connection.rollback();
                throw error;
            }
        } catch (SQLException error) {
            throw database(error);
        }
    }

    CloudArtifactSyncPage downloadArtifactSync(AuthenticatedUser actor, long afterCursor) {
        if (afterCursor < 0) throw new IllegalArgumentException("afterCursor must not be negative");
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement("""
            select cursor,operation_id,aggregate_type,aggregate_id,aggregate_version,payload_sha256,summary_json,
                occurred_at from v20_artifact_sync where actor_user_id=? and cursor>? order by cursor limit 200
            """)) {
            statement.setString(1, actor.id()); statement.setLong(2, afterCursor);
            List<CloudArtifactSyncItem> items = new ArrayList<>();
            long cursor = afterCursor;
            try (ResultSet row = statement.executeQuery()) {
                while (row.next()) {
                    cursor = row.getLong("cursor");
                    items.add(new CloudArtifactSyncItem(row.getString("operation_id"), row.getString("aggregate_type"),
                        row.getString("aggregate_id"), row.getLong("aggregate_version"),
                        row.getString("payload_sha256"), row.getString("summary_json"),
                        Instant.parse(row.getString("occurred_at")), cursor));
                }
            }
            return new CloudArtifactSyncPage(items, cursor);
        } catch (SQLException error) {
            throw database(error);
        }
    }

    private CloudArtifactSyncResult storeArtifactSync(Connection connection, String actorId,
                                                       CloudArtifactSyncItem item) throws SQLException {
        validateArtifactSyncItem(item);
        try (PreparedStatement existing = connection.prepareStatement("""
            select cursor,aggregate_type,aggregate_id,aggregate_version,payload_sha256
            from v20_artifact_sync where actor_user_id=? and operation_id=?
            """)) {
            existing.setString(1, actorId); existing.setString(2, item.operationId());
            try (ResultSet row = existing.executeQuery()) {
                if (row.next()) {
                    boolean same = row.getString("aggregate_type").equals(item.aggregateType())
                        && row.getString("aggregate_id").equals(item.aggregateId())
                        && row.getLong("aggregate_version") == item.aggregateVersion()
                        && row.getString("payload_sha256").equals(item.payloadSha256());
                    return new CloudArtifactSyncResult(item.operationId(), same
                        ? CloudArtifactSyncResult.Status.DUPLICATE : CloudArtifactSyncResult.Status.CONFLICT,
                        row.getLong("cursor"), row.getLong("aggregate_version"),
                        same ? "" : "SYNC_OPERATION_REUSED");
                }
            }
        }
        long currentVersion = -1;
        try (PreparedStatement current = connection.prepareStatement("""
            select max(aggregate_version) from v20_artifact_sync
            where actor_user_id=? and aggregate_type=? and aggregate_id=?
            """)) {
            current.setString(1, actorId); current.setString(2, item.aggregateType()); current.setString(3, item.aggregateId());
            try (ResultSet row = current.executeQuery()) {
                if (row.next() && row.getObject(1) != null) currentVersion = row.getLong(1);
            }
        }
        if (currentVersion >= item.aggregateVersion() || (currentVersion >= 0 && item.aggregateVersion() > currentVersion + 1)) {
            return new CloudArtifactSyncResult(item.operationId(), CloudArtifactSyncResult.Status.CONFLICT, 0,
                currentVersion, currentVersion >= item.aggregateVersion() ? "SYNC_STALE_VERSION" : "SYNC_VERSION_GAP");
        }
        try (PreparedStatement insert = connection.prepareStatement("""
            insert into v20_artifact_sync(actor_user_id,operation_id,aggregate_type,aggregate_id,aggregate_version,
                payload_sha256,summary_json,occurred_at) values(?,?,?,?,?,?,?,?)
            """, Statement.RETURN_GENERATED_KEYS)) {
            insert.setString(1, actorId); insert.setString(2, item.operationId()); insert.setString(3, item.aggregateType());
            insert.setString(4, item.aggregateId()); insert.setLong(5, item.aggregateVersion());
            insert.setString(6, item.payloadSha256()); insert.setString(7, item.summaryJson());
            insert.setString(8, item.occurredAt().toString()); insert.executeUpdate();
            try (ResultSet key = insert.getGeneratedKeys()) {
                long cursor = key.next() ? key.getLong(1) : 0;
                return new CloudArtifactSyncResult(item.operationId(), CloudArtifactSyncResult.Status.ACCEPTED,
                    cursor, item.aggregateVersion(), "");
            }
        }
    }

    private static void validateArtifactSyncItem(CloudArtifactSyncItem item) {
        Set<String> allowedTypes = Set.of("COURSE_VERSION", "ASSIGNMENT_SNAPSHOT", "EVALUATION_SUMMARY",
            "FEEDBACK", "PROJECT_METADATA");
        if (!allowedTypes.contains(item.aggregateType())) throw new IllegalArgumentException("Unknown sync aggregate type");
        if (item.summaryJson().getBytes(StandardCharsets.UTF_8).length > 16_384) {
            throw new IllegalArgumentException("Sync summary is too large");
        }
        try {
            var node = JSON.readTree(item.summaryJson());
            if (!node.isObject() || containsPrivateSyncField(node)) {
                throw new IllegalArgumentException("Sync summary contains disallowed fields");
            }
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("Sync summary JSON is invalid", error);
        }
    }

    private static boolean containsPrivateSyncField(com.fasterxml.jackson.databind.JsonNode node) {
        Set<String> forbidden = Set.of("source", "sourcecode", "code", "repositoryurl", "token", "password",
            "secret", "path", "content");
        var fields = node.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            if (forbidden.contains(field.getKey().replace("_", "").toLowerCase(Locale.ROOT))
                    || (field.getValue().isContainerNode() && containsPrivateSyncField(field.getValue()))) return true;
        }
        if (node.isArray()) for (var value : node) if (containsPrivateSyncField(value)) return true;
        return false;
    }

    private SecureCoursePackage parseSecureCoursePackage(String packageJson) {
        if (packageJson == null || packageJson.length() > 2_200_000) {
            throw new IllegalArgumentException("Course package is invalid or too large");
        }
        Map<String, Object> envelope;
        try {
            envelope = JSON.readValue(packageJson, new TypeReference<>() { });
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("Course package JSON is invalid", error);
        }
        if (!Integer.valueOf(2).equals(envelope.get("formatVersion"))) {
            throw new IllegalArgumentException("Unsupported secure course package format");
        }
        String packageId = required(String.valueOf(envelope.get("packageId")), "packageId", 160);
        String courseVersion = required(String.valueOf(envelope.get("courseVersion")), "courseVersion", 64);
        String license = required(String.valueOf(envelope.get("license")), "license", 160);
        String payload = String.valueOf(envelope.get("payloadJson"));
        if (payload.isBlank() || payload.length() > 2_000_000) {
            throw new IllegalArgumentException("payloadJson is invalid");
        }
        String claimed = required(String.valueOf(envelope.get("contentSha256")), "contentSha256", 64)
            .toLowerCase(Locale.ROOT);
        if (!claimed.matches("[0-9a-f]{64}") || !claimed.equals(sha256(payload))) {
            throw new IllegalArgumentException("Course package content hash is invalid");
        }
        return new SecureCoursePackage(packageId, courseVersion, license, claimed, payload);
    }

    private CoursePackagePreview.Conflict packageConflict(String actorId, SecureCoursePackage coursePackage) {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement("""
            select content_sha256 from v20_course_package_operations
            where actor_user_id=? and package_id=? and course_version=? order by created_at desc limit 1
            """)) {
            statement.setString(1, actorId); statement.setString(2, coursePackage.packageId());
            statement.setString(3, coursePackage.courseVersion());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return CoursePackagePreview.Conflict.NONE;
                return coursePackage.contentSha256().equals(row.getString(1))
                    ? CoursePackagePreview.Conflict.SAME_CONTENT : CoursePackagePreview.Conflict.VERSION_CONFLICT;
            }
        } catch (SQLException error) {
            throw database(error);
        }
    }

    private static List<?> boundedItems(Object value, int maximum, String name) {
        List<?> items = objectList(value);
        if (items.size() > maximum) throw new IllegalArgumentException("Too many course package " + name);
        return items;
    }

    private record SecureCoursePackage(String packageId, String courseVersion, String license,
                                       String contentSha256, String payloadJson) { }

    List<CloudKnowledgeArticle> listKnowledge(AuthenticatedUser actor, String courseId) {
        requireKnowledgeCourseVisible(actor, courseId);
        String sql = actor.hasRole(UserRole.ADMIN) || isCourseOwner(actor, courseId)
            ? "select * from cloud_knowledge_articles where course_id=? order by updated_at desc"
            : "select * from cloud_knowledge_articles where course_id=? and visibility='PUBLISHED' order by updated_at desc";
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, courseId);
            List<CloudKnowledgeArticle> result = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(cloudArticle(rows));
            }
            return List.copyOf(result);
        } catch (SQLException error) { throw database(error); }
    }

    List<CloudKnowledgeSearchHit> searchKnowledge(AuthenticatedUser actor, String courseId, String query, int limit) {
        requireKnowledgeCourseVisible(actor, courseId);
        String normalized = required(query, "query", 300);
        if (limit < 1 || limit > 50) throw new IllegalArgumentException("limit must be between 1 and 50");
        boolean owner = actor.hasRole(UserRole.ADMIN) || isCourseOwner(actor, courseId);
        String sql = """
            select a.id,a.title,a.current_revision,c.chunk_index,c.content
            from cloud_knowledge_chunks c join cloud_knowledge_articles a on a.id=c.article_id
            join cloud_knowledge_revisions r on r.id=c.revision_id and r.revision=a.current_revision
            where a.course_id=? and instr(lower(c.content),lower(?))>0
            """ + (owner ? "" : " and a.visibility='PUBLISHED'") + " order by a.updated_at desc,c.chunk_index limit ?";
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, courseId); statement.setString(2, normalized); statement.setInt(3, limit);
            List<CloudKnowledgeSearchHit> result = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(new CloudKnowledgeSearchHit(rows.getString(1), rows.getString(2),
                    rows.getInt(3), rows.getInt(4), excerpt(rows.getString(5), normalized), 1.0 / (result.size() + 1)));
            }
            return List.copyOf(result);
        } catch (SQLException error) { throw database(error); }
    }

    List<PendingKnowledgeChunk> pendingKnowledgeChunks(int limit) {
        if (limit < 1 || limit > 64) throw new IllegalArgumentException("limit must be between 1 and 64");
        String sql = """
            select o.id,c.id,a.id,a.course_id,a.visibility,a.current_revision,c.chunk_index,c.content,c.content_hash,o.attempts
            from cloud_knowledge_index_outbox o
            join cloud_knowledge_chunks c on c.id=o.chunk_id
            join cloud_knowledge_articles a on a.id=c.article_id
            join cloud_knowledge_revisions r on r.id=c.revision_id and r.revision=a.current_revision
            where o.operation='UPSERT' and o.status in ('PENDING','FAILED') and o.next_attempt_at<=?
            order by o.created_at,o.id limit ?
            """;
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, Instant.now().toString());
            statement.setInt(2, limit);
            List<PendingKnowledgeChunk> result = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(new PendingKnowledgeChunk(rows.getString(1), rows.getString(2), rows.getString(3),
                        rows.getString(4), rows.getString(5), rows.getInt(6), rows.getInt(7), rows.getString(8),
                        rows.getString(9), rows.getInt(10)));
                }
            }
            return List.copyOf(result);
        } catch (SQLException error) { throw database(error); }
    }

    void markKnowledgeIndexed(List<PendingKnowledgeChunk> chunks, String provider, String model,
                              int dimension, int indexVersion) {
        if (chunks == null || chunks.isEmpty()) return;
        Instant now = Instant.now();
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try (PreparedStatement chunk = connection.prepareStatement(
                     "update cloud_knowledge_chunks set index_status='INDEXED' where id=?");
                 PreparedStatement outbox = connection.prepareStatement(
                     "update cloud_knowledge_index_outbox set status='COMPLETED',last_error=null,updated_at=? where id=?");
                 PreparedStatement profile = connection.prepareStatement("""
                     insert into cloud_knowledge_embedding_profile(id,provider,model,dimension,index_version,updated_at)
                     values(1,?,?,?,?,?) on conflict(id) do update set provider=excluded.provider,model=excluded.model,
                     dimension=excluded.dimension,index_version=excluded.index_version,updated_at=excluded.updated_at
                     """)) {
                for (PendingKnowledgeChunk item : chunks) {
                    chunk.setString(1, item.chunkId()); chunk.addBatch();
                    outbox.setString(1, now.toString()); outbox.setString(2, item.outboxId()); outbox.addBatch();
                }
                chunk.executeBatch(); outbox.executeBatch();
                profile.setString(1, required(provider, "provider", 80));
                profile.setString(2, required(model, "model", 160));
                profile.setInt(3, dimension); profile.setInt(4, indexVersion); profile.setString(5, now.toString());
                profile.executeUpdate();
                connection.commit();
            } catch (SQLException | RuntimeException error) { connection.rollback(); throw error; }
        } catch (SQLException error) { throw database(error); }
    }

    void markKnowledgeIndexFailed(List<PendingKnowledgeChunk> chunks, String requestedError) {
        if (chunks == null || chunks.isEmpty()) return;
        String error = optional(requestedError, 500);
        Instant now = Instant.now();
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try (PreparedStatement chunk = connection.prepareStatement(
                     "update cloud_knowledge_chunks set index_status='FAILED' where id=?");
                 PreparedStatement outbox = connection.prepareStatement("""
                     update cloud_knowledge_index_outbox set status='FAILED',attempts=attempts+1,next_attempt_at=?,
                     last_error=?,updated_at=? where id=?
                     """)) {
                for (PendingKnowledgeChunk item : chunks) {
                    long delaySeconds = Math.min(3_600L, 5L << Math.min(9, item.attempts()));
                    chunk.setString(1, item.chunkId()); chunk.addBatch();
                    outbox.setString(1, now.plusSeconds(delaySeconds).toString());
                    outbox.setString(2, error); outbox.setString(3, now.toString());
                    outbox.setString(4, item.outboxId()); outbox.addBatch();
                }
                chunk.executeBatch(); outbox.executeBatch(); connection.commit();
            } catch (SQLException | RuntimeException failure) { connection.rollback(); throw failure; }
        } catch (SQLException failure) { throw database(failure); }
    }

    long pendingKnowledgeIndexCount() {
        try (Connection connection = open(); Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery(
                 "select count(*) from cloud_knowledge_index_outbox where status<>'COMPLETED'")) {
            return row.next() ? row.getLong(1) : 0;
        } catch (SQLException error) { throw database(error); }
    }

    List<String> allowedKnowledgeVisibility(AuthenticatedUser actor, String courseId) {
        requireKnowledgeCourseVisible(actor, courseId);
        if (actor.hasRole(UserRole.ADMIN) || isCourseOwner(actor, courseId)) {
            return List.of("PUBLISHED", "PRIVATE", "INACTIVE");
        }
        return List.of("PUBLISHED");
    }

    int requeueKnowledgeIndex() {
        Instant now = Instant.now();
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try (Statement chunks = connection.createStatement();
                 PreparedStatement outbox = connection.prepareStatement("""
                     insert into cloud_knowledge_index_outbox(id,chunk_id,operation,status,attempts,next_attempt_at,last_error,created_at,updated_at)
                     select lower(hex(randomblob(16))),c.id,'UPSERT','PENDING',0,?,null,?,?
                     from cloud_knowledge_chunks c join cloud_knowledge_articles a on a.id=c.article_id
                     join cloud_knowledge_revisions r on r.id=c.revision_id and r.revision=a.current_revision
                     on conflict(chunk_id) do update set operation='UPSERT',status='PENDING',attempts=0,
                     next_attempt_at=excluded.next_attempt_at,last_error=null,updated_at=excluded.updated_at
                     """)) {
                int count = chunks.executeUpdate("""
                    update cloud_knowledge_chunks set index_status='PENDING' where id in (
                    select c.id from cloud_knowledge_chunks c join cloud_knowledge_articles a on a.id=c.article_id
                    join cloud_knowledge_revisions r on r.id=c.revision_id and r.revision=a.current_revision)
                    """);
                outbox.setString(1, now.toString()); outbox.setString(2, now.toString());
                outbox.setString(3, now.toString()); outbox.executeUpdate();
                connection.commit();
                return count;
            } catch (SQLException | RuntimeException error) { connection.rollback(); throw error; }
        } catch (SQLException error) { throw database(error); }
    }

    List<CloudKnowledgeSearchHit> resolveAuthorizedVectorHits(AuthenticatedUser actor, String courseId,
                                                               String query, List<VectorCandidate> candidates,
                                                               int limit) {
        requireKnowledgeCourseVisible(actor, courseId);
        if (candidates == null || candidates.isEmpty()) return List.of();
        boolean owner = actor.hasRole(UserRole.ADMIN) || isCourseOwner(actor, courseId);
        Map<String, VectorCandidate> ordered = new LinkedHashMap<>();
        candidates.forEach(item -> ordered.putIfAbsent(item.chunkId(), item));
        String placeholders = String.join(",", java.util.Collections.nCopies(ordered.size(), "?"));
        String sql = """
            select c.id,a.id,a.title,a.current_revision,c.chunk_index,c.content,a.visibility
            from cloud_knowledge_chunks c join cloud_knowledge_articles a on a.id=c.article_id
            join cloud_knowledge_revisions r on r.id=c.revision_id and r.revision=a.current_revision
            where a.course_id=? and c.id in (%s)
            """.formatted(placeholders);
        Map<String, CloudKnowledgeSearchHit> found = new LinkedHashMap<>();
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, courseId);
            int parameter = 2;
            for (String id : ordered.keySet()) statement.setString(parameter++, id);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String chunkId = rows.getString(1);
                    if (!owner && !"PUBLISHED".equals(rows.getString(7))) continue;
                    VectorCandidate candidate = ordered.get(chunkId);
                    found.put(chunkId, new CloudKnowledgeSearchHit(rows.getString(2), rows.getString(3),
                        rows.getInt(4), rows.getInt(5), excerpt(rows.getString(6), query), candidate.score()));
                }
            }
        } catch (SQLException error) { throw database(error); }
        return ordered.keySet().stream().map(found::get).filter(Objects::nonNull).limit(limit).toList();
    }

    private void initialize() throws SQLException {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("create table if not exists courses(id text primary key,name text not null,"
                + "description text not null default '',status text not null check(status in ('ACTIVE','INACTIVE')),"
                + "version integer not null,created_by text not null references users(id),created_at text not null,updated_at text not null)");
            statement.executeUpdate("create table if not exists course_sections(id text primary key,course_id text not null references courses(id),"
                + "name text not null,sort_order integer not null,status text not null check(status in ('ACTIVE','INACTIVE')),"
                + "version integer not null,created_at text not null,updated_at text not null)");
            statement.executeUpdate("create table if not exists knowledge_points(id text primary key,course_id text not null references courses(id),"
                + "section_id text references course_sections(id),name text not null,description text not null default '',"
                + "sort_order integer not null,status text not null check(status in ('ACTIVE','INACTIVE')),version integer not null,"
                + "created_at text not null,updated_at text not null)");
            statement.executeUpdate("create table if not exists shared_exercises(id text primary key,course_id text not null references courses(id),"
                + "current_version integer not null,status text not null check(status in ('ACTIVE','INACTIVE')),"
                + "created_by text not null references users(id),created_at text not null,updated_at text not null)");
            statement.executeUpdate("create table if not exists shared_exercise_versions(id text primary key,exercise_id text not null references shared_exercises(id),"
                + "course_id text not null references courses(id),version integer not null,title text not null,prompt text not null,"
                + "dataset_version text not null,evaluation_rule text not null,knowledge_point_ids_json text not null,"
                + "content_hash text not null,created_by text not null references users(id),published_at text not null,"
                + "unique(exercise_id,version))");
            statement.executeUpdate("create table if not exists assignment_content_snapshots(assignment_id text primary key references class_assignments(id),"
                + "exercise_version_id text not null references shared_exercise_versions(id),title text not null,prompt text not null,"
                + "dataset_version text not null,evaluation_rule text not null,knowledge_point_ids_json text not null,"
                + "snapshot_hash text not null,created_at text not null)");
            statement.executeUpdate("create table if not exists submission_feedback(submission_id text primary key references assignment_submissions(id),"
                + "assignment_id text not null references class_assignments(id),student_user_id text not null references users(id),"
                + "status text not null check(status in ('NEEDS_WORK','REVIEWED','RESOLVED')),comment text not null,"
                + "knowledge_point_ids_json text not null,version integer not null,author_user_id text not null references users(id),updated_at text not null)");
            statement.executeUpdate("create table if not exists feedback_audit(id text primary key,submission_id text not null references assignment_submissions(id),"
                + "actor_user_id text not null references users(id),status text not null,version integer not null,created_at text not null)");
            statement.executeUpdate("create table if not exists cloud_notifications(id text primary key,recipient_user_id text not null references users(id),"
                + "type text not null,resource_type text not null,resource_id text not null,title text not null,message text not null,"
                + "idempotency_key text not null,read_at text,created_at text not null,unique(recipient_user_id,idempotency_key))");
            statement.executeUpdate("create table if not exists v14_operations(actor_user_id text not null references users(id),"
                + "operation_id text not null,resource_type text not null,resource_id text not null,created_at text not null,"
                + "primary key(actor_user_id,operation_id))");
            statement.executeUpdate("create table if not exists cloud_schema_version(version integer primary key,"
                + "description text not null,applied_at text not null)");
            statement.executeUpdate("insert or ignore into cloud_schema_version(version,description,applied_at) values"
                + "(1,'v1.3 cloud baseline',current_timestamp)");
            statement.executeUpdate("insert or ignore into cloud_schema_version(version,description,applied_at) values"
                + "(2,'v1.4 course content and feedback',current_timestamp)");
            statement.executeUpdate("create table if not exists cloud_knowledge_articles(id text primary key,course_id text not null references courses(id),"
                + "section_id text references course_sections(id),title text not null,visibility text not null check(visibility in ('PRIVATE','PUBLISHED','INACTIVE')),"
                + "current_revision integer not null,content_hash text not null,created_by text not null references users(id),created_at text not null,updated_at text not null)");
            statement.executeUpdate("create table if not exists cloud_knowledge_revisions(id text primary key,article_id text not null references cloud_knowledge_articles(id) on delete cascade,"
                + "revision integer not null,content text not null,content_hash text not null,created_by text not null references users(id),created_at text not null,unique(article_id,revision))");
            statement.executeUpdate("create table if not exists cloud_knowledge_chunks(id text primary key,article_id text not null references cloud_knowledge_articles(id) on delete cascade,"
                + "revision_id text not null references cloud_knowledge_revisions(id) on delete cascade,chunk_index integer not null,content text not null,content_hash text not null,"
                + "index_status text not null check(index_status in ('PENDING','INDEXED','FAILED')),unique(revision_id,chunk_index))");
            statement.executeUpdate("create table if not exists cloud_knowledge_sync_cursor(user_id text not null references users(id),course_id text not null references courses(id),"
                + "cursor_version integer not null,updated_at text not null,primary key(user_id,course_id))");
            statement.executeUpdate("insert or ignore into cloud_schema_version(version,description,applied_at) values"
                + "(3,'v1.8.5 shared knowledge and vector indexing metadata',current_timestamp)");
            statement.executeUpdate("create table if not exists cloud_knowledge_index_outbox(id text primary key,"
                + "chunk_id text not null unique references cloud_knowledge_chunks(id) on delete cascade,operation text not null check(operation in ('UPSERT','DELETE')),"
                + "status text not null check(status in ('PENDING','FAILED','COMPLETED')),attempts integer not null default 0,next_attempt_at text not null,"
                + "last_error text,created_at text not null,updated_at text not null)");
            statement.executeUpdate("create table if not exists cloud_knowledge_embedding_profile(id integer primary key check(id=1),"
                + "provider text not null,model text not null,dimension integer not null,index_version integer not null,updated_at text not null)");
            statement.executeUpdate("insert or ignore into cloud_knowledge_index_outbox(id,chunk_id,operation,status,attempts,next_attempt_at,created_at,updated_at) "
                + "select lower(hex(randomblob(16))),c.id,'UPSERT','PENDING',0,current_timestamp,current_timestamp,current_timestamp "
                + "from cloud_knowledge_chunks c join cloud_knowledge_articles a on a.id=c.article_id "
                + "join cloud_knowledge_revisions r on r.id=c.revision_id and r.revision=a.current_revision");
            statement.executeUpdate("insert or ignore into cloud_schema_version(version,description,applied_at) values"
                + "(4,'v1.8.5 qdrant embedding outbox and profile',current_timestamp)");
            statement.executeUpdate("create index if not exists idx_course_sections_order on course_sections(course_id,sort_order,id)");
            statement.executeUpdate("create index if not exists idx_knowledge_points_order on knowledge_points(course_id,sort_order,id)");
            statement.executeUpdate("create index if not exists idx_shared_exercise_course on shared_exercises(course_id,status,updated_at desc)");
            statement.executeUpdate("create index if not exists idx_feedback_assignment on submission_feedback(assignment_id,updated_at desc)");
            statement.executeUpdate("create index if not exists idx_notification_recipient on cloud_notifications(recipient_user_id,created_at desc)");
            statement.executeUpdate("create index if not exists idx_cloud_knowledge_scope on cloud_knowledge_articles(course_id,visibility,updated_at desc)");
            statement.executeUpdate("create index if not exists idx_cloud_knowledge_chunks on cloud_knowledge_chunks(article_id,revision_id,chunk_index)");
            statement.executeUpdate("create index if not exists idx_cloud_knowledge_outbox_pending on cloud_knowledge_index_outbox(status,next_attempt_at,created_at)");
            statement.executeUpdate("create table if not exists v20_course_package_operations(actor_user_id text not null references users(id),"
                + "operation_id text not null,package_id text not null,course_id text not null references courses(id),course_version text not null,"
                + "content_sha256 text not null,license text not null,created_at text not null,primary key(actor_user_id,operation_id),"
                + "unique(actor_user_id,package_id,course_version))");
            statement.executeUpdate("create table if not exists v20_artifact_sync(cursor integer primary key autoincrement,"
                + "actor_user_id text not null references users(id),operation_id text not null,aggregate_type text not null,aggregate_id text not null,"
                + "aggregate_version integer not null,payload_sha256 text not null,summary_json text not null,occurred_at text not null,"
                + "unique(actor_user_id,operation_id),unique(actor_user_id,aggregate_type,aggregate_id,aggregate_version))");
            statement.executeUpdate("create index if not exists idx_v20_artifact_sync_owner_cursor on v20_artifact_sync(actor_user_id,cursor)");
            statement.executeUpdate("insert or ignore into cloud_schema_version(version,description,applied_at) values"
                + "(6,'v2.0 secure course packages and capability sync',current_timestamp)");
        }
    }

    private boolean isCourseOwner(AuthenticatedUser actor, String courseId) {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
            "select 1 from courses where id=? and created_by=?")) {
            statement.setString(1, courseId); statement.setString(2, actor.id());
            try (ResultSet rows = statement.executeQuery()) { return rows.next(); }
        } catch (SQLException error) { throw database(error); }
    }

    private void requireKnowledgeCourseVisible(AuthenticatedUser actor, String courseId) {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
            "select created_by,status from courses where id=?")) {
            statement.setString(1, courseId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw new IllegalArgumentException("Course not found");
                boolean owner = actor.hasRole(UserRole.ADMIN) || actor.id().equals(rows.getString(1));
                if (!owner && !ContentStatus.ACTIVE.name().equals(rows.getString(2))) {
                    throw new SecurityException("course is not visible");
                }
            }
        } catch (SQLException error) { throw database(error); }
    }

    private static CloudKnowledgeArticle cloudArticle(ResultSet rows) throws SQLException {
        return new CloudKnowledgeArticle(rows.getString("id"), rows.getString("course_id"),
            Objects.toString(rows.getString("section_id"), ""), rows.getString("title"), rows.getString("content_hash"),
            rows.getString("visibility"), rows.getInt("current_revision"), rows.getString("created_by"),
            Instant.parse(rows.getString("updated_at")));
    }

    private static List<String> cloudChunks(String content) {
        List<String> result = new ArrayList<>();
        for (int offset = 0; offset < content.length(); offset += 1_200) {
            result.add(content.substring(offset, Math.min(content.length(), offset + 1_200)));
        }
        return List.copyOf(result);
    }

    private static String excerpt(String content, String query) {
        int match = content.toLowerCase(Locale.ROOT).indexOf(query.toLowerCase(Locale.ROOT));
        int start = Math.max(0, match - 120), end = Math.min(content.length(), match + query.length() + 240);
        return (start > 0 ? "…" : "") + content.substring(start, end).replaceAll("\\s+", " ") + (end < content.length() ? "…" : "");
    }

    private Connection open() throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("pragma foreign_keys=on");
            statement.executeUpdate("pragma busy_timeout=5000");
        }
        return connection;
    }

    private void requireTeacherRole(AuthenticatedUser actor) {
        if (!actor.hasRole(UserRole.TEACHER) && !actor.hasRole(UserRole.ADMIN)) {
            throw new SecurityException("teacher role required");
        }
    }

    private void requireCourseVisible(AuthenticatedUser actor, String courseId) {
        requireTeacherRole(actor);
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
            "select created_by,status from courses where id=?")) {
            statement.setString(1, courseId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new IllegalArgumentException("Course not found");
                if (!actor.hasRole(UserRole.ADMIN) && !actor.id().equals(row.getString(1))
                    && !ContentStatus.ACTIVE.name().equals(row.getString(2))) throw new SecurityException("course is not visible");
            }
        } catch (SQLException error) {
            throw database(error);
        }
    }

    private void requireCourseOwner(AuthenticatedUser actor, String courseId) {
        requireTeacherRole(actor);
        if (actor.hasRole(UserRole.ADMIN)) return;
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
            "select created_by from courses where id=?")) {
            statement.setString(1, courseId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new IllegalArgumentException("Course not found");
                if (!actor.id().equals(row.getString(1))) throw new SecurityException("course owner required");
            }
        } catch (SQLException error) {
            throw database(error);
        }
    }

    private void requireClassTeacher(AuthenticatedUser actor, String classroomId) {
        if (actor.hasRole(UserRole.ADMIN)) return;
        requireMembership(actor.id(), classroomId, "TEACHER");
    }

    private boolean isClassTeacher(AuthenticatedUser actor, String classroomId) {
        if (actor.hasRole(UserRole.ADMIN)) return true;
        return hasMembership(actor.id(), classroomId, "TEACHER");
    }

    private void requireClassStudent(AuthenticatedUser actor, String classroomId) {
        requireMembership(actor.id(), classroomId, "STUDENT");
    }

    private void requireClassMember(AuthenticatedUser actor, String classroomId) {
        if (actor.hasRole(UserRole.ADMIN)) return;
        if (!hasMembership(actor.id(), classroomId, null)) throw new SecurityException("classroom membership required");
    }

    private void requireStudentId(String classroomId, String studentId) {
        requireMembership(studentId, classroomId, "STUDENT");
    }

    private void requireMembership(String userId, String classroomId, String role) {
        if (!hasMembership(userId, classroomId, role)) throw new SecurityException("classroom membership required");
    }

    private boolean hasMembership(String userId, String classroomId, String role) {
        String sql = "select 1 from classroom_members where classroom_id=? and user_id=?"
            + (role == null ? "" : " and role=?");
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, classroomId);
            statement.setString(2, userId);
            if (role != null) statement.setString(3, role);
            try (ResultSet row = statement.executeQuery()) {
                return row.next();
            }
        } catch (SQLException error) {
            throw database(error);
        }
    }

    private void requireSection(Connection connection, String courseId, String sectionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "select 1 from course_sections where id=? and course_id=? and status='ACTIVE'")) {
            statement.setString(1, sectionId);
            statement.setString(2, courseId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new IllegalArgumentException("Section is not active in this course");
            }
        }
    }

    private void requireKnowledgePoints(Connection connection, String courseId, List<String> ids) throws SQLException {
        for (String id : normalizeIds(ids)) {
            try (PreparedStatement statement = connection.prepareStatement(
                "select 1 from knowledge_points where id=? and course_id=? and status='ACTIVE'")) {
                statement.setString(1, id);
                statement.setString(2, courseId);
                try (ResultSet row = statement.executeQuery()) {
                    if (!row.next()) throw new IllegalArgumentException("Knowledge point is not active in this course");
                }
            }
        }
    }

    private CourseCatalog findCourse(Connection connection, String id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("select * from courses where id=?")) {
            statement.setString(1, id);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new IllegalArgumentException("Course not found");
                return course(row);
            }
        }
    }

    private KnowledgePoint findKnowledgePoint(Connection connection, String id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("select * from knowledge_points where id=?")) {
            statement.setString(1, id);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new IllegalArgumentException("Knowledge point not found");
                return knowledgePoint(row);
            }
        }
    }

    private SharedExerciseVersion findExerciseVersion(Connection connection, String id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "select v.*,e.status from shared_exercise_versions v join shared_exercises e on e.id=v.exercise_id where v.id=?")) {
            statement.setString(1, id);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new IllegalArgumentException("Exercise version not found");
                return exerciseVersion(row);
            }
        }
    }

    private AssignmentContentSnapshot findSnapshot(Connection connection, String assignmentId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "select * from assignment_content_snapshots where assignment_id=?")) {
            statement.setString(1, assignmentId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new IllegalArgumentException("Assignment snapshot was not found");
                return snapshot(row);
            }
        }
    }

    private ClassAssignment assignment(Connection connection, String classroomId, String assignmentId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "select * from class_assignments where classroom_id=? and id=?")) {
            statement.setString(1, classroomId);
            statement.setString(2, assignmentId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new IllegalArgumentException("Assignment not found");
                Instant created = Instant.parse(row.getString("created_at"));
                return new ClassAssignment(row.getString("id"), row.getString("classroom_id"),
                    row.getString("exercise_id"), row.getString("title"), created,
                    AssignmentStatus.valueOf(row.getString("status")), instant(row.getString("due_at")),
                    instant(row.getString("updated_at")), row.getString("description"),
                    instant(row.getString("published_at")), row.getString("copied_from_assignment_id"),
                    row.getLong("version"));
            }
        }
    }

    private SubmissionIdentity submissionIdentity(Connection connection, String classroomId, String assignmentId,
                                                  String submissionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "select user_id,status,error_code from assignment_submissions where id=? and classroom_id=? and assignment_id=?")) {
            statement.setString(1, submissionId);
            statement.setString(2, classroomId);
            statement.setString(3, assignmentId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new IllegalArgumentException("Submission not found");
                return new SubmissionIdentity(row.getString(1), "PASSED".equals(row.getString(2)), row.getString(3));
            }
        }
    }

    private long feedbackVersion(Connection connection, String submissionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "select version from submission_feedback where submission_id=?")) {
            statement.setString(1, submissionId);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? row.getLong(1) : 0;
            }
        }
    }

    private SubmissionFeedback feedback(Connection connection, String submissionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "select * from submission_feedback where submission_id=?")) {
            statement.setString(1, submissionId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new IllegalArgumentException("Feedback not found");
                return feedback(row);
            }
        }
    }

    private String operationResource(Connection connection, String actorId, String operationId, String type)
        throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "select resource_type,resource_id from v14_operations where actor_user_id=? and operation_id=?")) {
            statement.setString(1, actorId);
            statement.setString(2, operationId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return null;
                if (!type.equals(row.getString(1))) throw new V14VersionConflictException("Operation ID was reused");
                return row.getString(2);
            }
        }
    }

    private void recordOperation(Connection connection, String actorId, String operationId, String type, String id)
        throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "insert into v14_operations(actor_user_id,operation_id,resource_type,resource_id,created_at) values(?,?,?,?,?)")) {
            statement.setString(1, actorId);
            statement.setString(2, operationId);
            statement.setString(3, type);
            statement.setString(4, id);
            statement.setString(5, Instant.now().toString());
            statement.executeUpdate();
        }
    }

    private static CourseCatalog course(ResultSet row) throws SQLException {
        return new CourseCatalog(row.getString("id"), row.getString("name"), row.getString("description"),
            ContentStatus.valueOf(row.getString("status")), row.getLong("version"), row.getString("created_by"),
            Instant.parse(row.getString("created_at")), Instant.parse(row.getString("updated_at")));
    }

    private static CourseSection section(ResultSet row) throws SQLException {
        return new CourseSection(row.getString("id"), row.getString("course_id"), row.getString("name"),
            row.getInt("sort_order"), ContentStatus.valueOf(row.getString("status")), row.getLong("version"),
            Instant.parse(row.getString("created_at")), Instant.parse(row.getString("updated_at")));
    }

    private static KnowledgePoint knowledgePoint(ResultSet row) throws SQLException {
        return new KnowledgePoint(row.getString("id"), row.getString("course_id"), row.getString("section_id"),
            row.getString("name"), row.getString("description"), row.getInt("sort_order"),
            ContentStatus.valueOf(row.getString("status")), row.getLong("version"),
            Instant.parse(row.getString("created_at")), Instant.parse(row.getString("updated_at")));
    }

    private static SharedExerciseVersion exerciseVersion(ResultSet row) throws SQLException {
        return new SharedExerciseVersion(row.getString("id"), row.getString("exercise_id"), row.getString("course_id"),
            row.getInt("version"), row.getString("title"), row.getString("prompt"), row.getString("dataset_version"),
            row.getString("evaluation_rule"), readIds(row.getString("knowledge_point_ids_json")),
            row.getString("content_hash"), ContentStatus.valueOf(row.getString("status")),
            row.getString("created_by"), Instant.parse(row.getString("published_at")));
    }

    private static AssignmentContentSnapshot snapshot(ResultSet row) throws SQLException {
        return new AssignmentContentSnapshot(row.getString("assignment_id"), row.getString("exercise_version_id"),
            row.getString("title"), row.getString("prompt"), row.getString("dataset_version"),
            row.getString("evaluation_rule"), readIds(row.getString("knowledge_point_ids_json")),
            row.getString("snapshot_hash"), Instant.parse(row.getString("created_at")));
    }

    private static SubmissionFeedback feedback(ResultSet row) throws SQLException {
        return new SubmissionFeedback(row.getString("submission_id"), row.getString("assignment_id"),
            row.getString("student_user_id"), FeedbackStatus.valueOf(row.getString("status")), row.getString("comment"),
            readIds(row.getString("knowledge_point_ids_json")), row.getLong("version"),
            row.getString("author_user_id"), Instant.parse(row.getString("updated_at")));
    }

    private static CloudNotification notification(ResultSet row) throws SQLException {
        return new CloudNotification(row.getString("id"), NotificationType.valueOf(row.getString("type")),
            row.getString("resource_type"), row.getString("resource_id"), row.getString("title"),
            row.getString("message"), instant(row.getString("read_at")), Instant.parse(row.getString("created_at")));
    }

    private static void bindFeedback(PreparedStatement statement, String submissionId, String assignmentId,
                                     String studentId, FeedbackStatus status, String comment, List<String> pointIds,
                                     long version, String authorId, Instant updatedAt) throws SQLException {
        statement.setString(1, submissionId);
        statement.setString(2, assignmentId);
        statement.setString(3, studentId);
        statement.setString(4, status.name());
        statement.setString(5, comment);
        statement.setString(6, writeJson(pointIds));
        statement.setLong(7, version);
        statement.setString(8, authorId);
        statement.setString(9, updatedAt.toString());
    }

    private static List<String> normalizeIds(List<String> ids) {
        if (ids == null) return List.of();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String id : ids) {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("Knowledge point ID must not be blank");
            result.add(id.trim());
        }
        if (result.size() > 20) throw new IllegalArgumentException("At most 20 knowledge points are allowed");
        return List.copyOf(result);
    }

    private static List<String> readIds(String json) {
        try {
            return JSON.readValue(json, new TypeReference<>() { });
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Stored knowledge point list is invalid", error);
        }
    }

    private static String writeJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("JSON serialization failed", error);
        }
    }

    private static String sha256(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static String required(String value, String name, int max) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw new IllegalArgumentException(name + " must contain 1 to " + max + " characters");
        }
        return value.trim();
    }

    private static String optional(String value, int max) {
        if (value == null) return "";
        if (value.length() > max) throw new IllegalArgumentException("Text must contain at most " + max + " characters");
        return value.trim();
    }

    private static String requiredOperation(String operationId) {
        return required(operationId, "operationId", 120);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static Instant instant(String value) {
        return value == null ? null : Instant.parse(value);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> objectMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) throw new IllegalArgumentException("Course bundle object is invalid");
        return (Map<String, Object>) map;
    }

    private static List<?> objectList(Object value) {
        if (!(value instanceof List<?> list)) throw new IllegalArgumentException("Course bundle list is invalid");
        return list;
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().map(String::valueOf).toList();
    }

    private static int intValue(Object value) {
        if (value instanceof Number number) return number.intValue();
        return Integer.parseInt(String.valueOf(value));
    }

    private static IllegalStateException database(SQLException error) {
        return new IllegalStateException("v1.4 cloud database operation failed", error);
    }

    private static final class MutableMastery {
        private int attempts;
        private int passes;
    }

    private record SubmissionIdentity(String studentUserId, boolean passed, String errorCode) { }
}

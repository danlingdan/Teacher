package com.sqlteacher.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlteacher.application.collaboration.AssignmentAnalyticsFilter;
import com.sqlteacher.application.collaboration.AssignmentAnalyticsReport;
import com.sqlteacher.application.collaboration.AssignmentAnalyticsRow;
import com.sqlteacher.application.collaboration.AssignmentErrorCount;
import com.sqlteacher.application.collaboration.AssignmentStudentStatus;
import com.sqlteacher.application.collaboration.AssignmentStatus;
import com.sqlteacher.application.collaboration.AssignmentSubmission;
import com.sqlteacher.application.collaboration.AssignmentSubmissionRequest;
import com.sqlteacher.application.collaboration.AssignmentSubmissionRejectedException;
import com.sqlteacher.application.collaboration.AssignmentSubmissionStatus;
import com.sqlteacher.application.collaboration.AssignmentVersionConflictException;
import com.sqlteacher.application.collaboration.AuthenticatedUser;
import com.sqlteacher.application.collaboration.ClassAssignment;
import com.sqlteacher.application.collaboration.ClassLearningSummary;
import com.sqlteacher.application.collaboration.ClassroomService;
import com.sqlteacher.application.collaboration.CloudSyncItem;
import com.sqlteacher.application.collaboration.SubmissionOperationConflictException;
import com.sqlteacher.application.collaboration.UserRole;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import java.security.SecureRandom;

/**
 * Cloud classroom, assignment, submission, learning-event sync, and learning-record export
 * persistence (v3.4.0 REF-2). Behavior is unchanged from the former {@code CloudStore} inner
 * class of {@link SqlTeacherCloudServer}; deterministic rules such as mastery-adjacent queue
 * inputs, version conflicts, and audit rows stay enforced here in Java.
 */
final class CloudClassroomStore extends CloudStoreBase implements ClassroomService {
    // Keeps the historical log category of the former SqlTeacherCloudServer nested store so
    // existing production log tooling is unaffected by the v3.4.0 file split.
    private static final Logger log = LoggerFactory.getLogger(SqlTeacherCloudServer.class);
    private static final ObjectMapper JSON = CloudJsonStoreSupport.mapper();
    private static final String ASSIGNMENT_COLUMNS = "id,exercise_id,title,description,created_at,status,"
        + "due_at,updated_at,published_at,copied_from_assignment_id,version";
    private static final String SUBMISSION_COLUMNS = "id,operation_id,classroom_id,assignment_id,user_id,"
        + "attempt_number,status,result_hash,error_code,client_completed_at,submitted_at";
    private static final int MAX_SYNC_ITEM_PAYLOAD_BYTES = 16_384;
    /** 班级码字符集：去除 0/O/1/I/L 等易混字符，便于课堂口头/板书传递（v3.4.1 CLS-1）。 */
    private static final String JOIN_CODE_ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";
    private static final int JOIN_CODE_LENGTH = 8;
    private static final SecureRandom JOIN_CODE_RANDOM = new SecureRandom();

    private final Clock clock;

    CloudClassroomStore(Path database) throws SQLException, IOException {
        this(database, Clock.systemUTC());
    }

    /** Test overload: makes the classroom time source injectable without changing behavior. */
    CloudClassroomStore(Path database, Clock clock) throws SQLException, IOException {
        super(database);
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        backfillJoinCodes();
    }

    /** Rejects one sync item whose payloadJson exceeds the advertised per-item limit. */
    static final class SyncItemPayloadTooLargeException extends IllegalArgumentException {
        SyncItemPayloadTooLargeException(String message) {
            super(message);
        }
    }

    @Override public Classroom create(AuthenticatedUser actor, String name) {
        if (!(actor.hasRole(UserRole.TEACHER) || actor.hasRole(UserRole.ADMIN))) throw new SecurityException("teacher role required");
        if (name == null || name.isBlank() || name.length() > 100) throw new IllegalArgumentException("name must be 1 to 100 characters");
        String id = UUID.randomUUID().toString();
        Instant now = clock.instant();
        try (Connection connection = open();
             PreparedStatement classroom = connection.prepareStatement("insert into classrooms(id,name,created_at,join_code) values(?,?,?,?)");
             PreparedStatement member = connection.prepareStatement("insert into classroom_members(classroom_id,user_id,role) values(?,?,?)")) {
            connection.setAutoCommit(false);
            classroom.setString(1, id);
            classroom.setString(2, name.trim());
            classroom.setString(3, now.toString());
            classroom.setString(4, newJoinCode(connection));
            classroom.executeUpdate();
            member.setString(1, id);
            member.setString(2, actor.id());
            member.setString(3, UserRole.TEACHER.name());
            member.executeUpdate();
            connection.commit();
        } catch (SQLException error) { throw database(error); }
        return classroom(id);
    }

    @Override public Classroom addMember(AuthenticatedUser actor, String classroomId, String userId, UserRole role) {
        if (role == null || role == UserRole.ADMIN) throw new IllegalArgumentException("Only TEACHER or STUDENT can join a classroom");
        requireTeacher(actor, classroomId);
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(
                 "insert into classroom_members(classroom_id,user_id,role) values(?,?,?) "
                     + "on conflict(classroom_id,user_id) do update set role=excluded.role");
             PreparedStatement promoteTeacher = connection.prepareStatement(
                 "insert or ignore into user_roles(user_id,role) values(?, 'TEACHER')")) {
            connection.setAutoCommit(false);
            statement.setString(1, classroomId);
            statement.setString(2, userId);
            statement.setString(3, role.name());
            statement.executeUpdate();
            if (role == UserRole.TEACHER) {
                promoteTeacher.setString(1, userId);
                promoteTeacher.executeUpdate();
            }
            connection.commit();
        } catch (SQLException error) { throw database(error); }
        return classroom(classroomId);
    }

    @Override public List<Classroom> listVisibleTo(AuthenticatedUser actor) {
        List<Classroom> classrooms = new ArrayList<>();
        String sql = actor.hasRole(UserRole.ADMIN)
            ? "select id from classrooms order by created_at desc"
            : "select c.id from classrooms c join classroom_members m on m.classroom_id=c.id where m.user_id=? order by c.created_at desc";
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            if (!actor.hasRole(UserRole.ADMIN)) statement.setString(1, actor.id());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) classrooms.add(classroom(result.getString(1)));
            }
        } catch (SQLException error) { throw database(error); }
        return List.copyOf(classrooms);
    }

    // ── v3.4.1 CLS-1：班级码。码为课堂公开物，明文存储；加入者固定授予 STUDENT 角色，
    // 教师成员仍走 addMember 的教师邮箱通道，重置后旧码立即失效。──

    /** 登录用户凭班级码以 STUDENT 身份加入班级；已是成员时幂等返回，不改既有角色。 */
    Classroom joinByCode(AuthenticatedUser actor, String code) {
        if (code == null || code.isBlank() || code.trim().length() > JOIN_CODE_LENGTH) {
            throw new IllegalArgumentException("join code must be 1 to " + JOIN_CODE_LENGTH + " characters");
        }
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        String classroomId = classroomIdByCode(normalized);
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
            "insert into classroom_members(classroom_id,user_id,role) values(?,?,'STUDENT') "
                + "on conflict(classroom_id,user_id) do nothing")) {
            statement.setString(1, classroomId);
            statement.setString(2, actor.id());
            statement.executeUpdate();
        } catch (SQLException error) { throw database(error); }
        return classroom(classroomId);
    }

    /** 班级教师读取当前班级码。 */
    String joinCode(AuthenticatedUser actor, String classroomId) {
        requireTeacher(actor, classroomId);
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
            "select join_code from classrooms where id=?")) {
            statement.setString(1, classroomId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next() || row.getString(1) == null) throw new IllegalArgumentException("Classroom not found");
                return row.getString(1);
            }
        } catch (SQLException error) { throw database(error); }
    }

    /** 班级教师重置班级码，旧码立即失效，返回新码。 */
    String rotateJoinCode(AuthenticatedUser actor, String classroomId) {
        requireTeacher(actor, classroomId);
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
            "update classrooms set join_code=? where id=?")) {
            connection.setAutoCommit(false);
            String code = newJoinCode(connection);
            statement.setString(1, code);
            statement.setString(2, classroomId);
            statement.executeUpdate();
            connection.commit();
            return code;
        } catch (SQLException error) { throw database(error); }
    }

    private String classroomIdByCode(String normalizedCode) {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
            "select id from classrooms where join_code=?")) {
            statement.setString(1, normalizedCode);
            try (ResultSet found = statement.executeQuery()) {
                if (!found.next()) throw new IllegalArgumentException("Unknown join code");
                return found.getString(1);
            }
        } catch (SQLException error) { throw database(error); }
    }

    /** 启动回填：Migration 9 加列后，存量班级在 store 构造时补齐短码（幂等）。 */
    private void backfillJoinCodes() {
        List<String> unassigned = new ArrayList<>();
        try (Connection connection = open();
             PreparedStatement missing = connection.prepareStatement(
                 "select id from classrooms where join_code is null");
             PreparedStatement assign = connection.prepareStatement(
                 "update classrooms set join_code=? where id=? and join_code is null")) {
            try (ResultSet rows = missing.executeQuery()) {
                while (rows.next()) unassigned.add(rows.getString(1));
            }
            connection.setAutoCommit(false);
            for (String id : unassigned) {
                assign.setString(1, newJoinCode(connection));
                assign.setString(2, id);
                assign.executeUpdate();
            }
            connection.commit();
        } catch (SQLException error) { throw database(error); }
        if (!unassigned.isEmpty()) {
            log.info("Backfilled join codes for {} existing classroom(s)", unassigned.size());
        }
    }

    private static String newJoinCode(Connection connection) throws SQLException {
        while (true) {
            String code = randomJoinCode();
            try (PreparedStatement probe = connection.prepareStatement("select 1 from classrooms where join_code=?")) {
                probe.setString(1, code);
                try (ResultSet found = probe.executeQuery()) {
                    if (!found.next()) return code;
                }
            }
        }
    }

    private static String randomJoinCode() {
        StringBuilder code = new StringBuilder(JOIN_CODE_LENGTH);
        for (int index = 0; index < JOIN_CODE_LENGTH; index++) {
            code.append(JOIN_CODE_ALPHABET.charAt(JOIN_CODE_RANDOM.nextInt(JOIN_CODE_ALPHABET.length())));
        }
        return code.toString();
    }

    int upload(AuthenticatedUser actor, List<CloudSyncItem> items) {
        if (items.size() > 500) throw new IllegalArgumentException("A sync batch may contain at most 500 items");
        for (int index = 0; index < items.size(); index++) {
            String payload = items.get(index).payloadJson();
            int size = payload == null ? 0 : payload.getBytes(StandardCharsets.UTF_8).length;
            if (size > MAX_SYNC_ITEM_PAYLOAD_BYTES) {
                throw new SyncItemPayloadTooLargeException("Sync item " + (index + 1)
                    + " payloadJson exceeds the limit of " + MAX_SYNC_ITEM_PAYLOAD_BYTES + " bytes");
            }
        }
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
            "insert into sync_events(user_id,event_id,event_type,payload_json,occurred_at) values(?,?,?,?,?) "
                + "on conflict(user_id,event_id) do update set event_type=excluded.event_type,payload_json=excluded.payload_json,occurred_at=excluded.occurred_at")) {
            connection.setAutoCommit(false);
            for (CloudSyncItem item : items) {
                statement.setString(1, actor.id());
                statement.setString(2, item.id());
                statement.setString(3, item.type());
                statement.setString(4, item.payloadJson());
                statement.setString(5, item.occurredAt().toString());
                statement.addBatch();
            }
            statement.executeBatch();
            connection.commit();
            return items.size();
        } catch (SQLException error) { throw database(error); }
    }

    List<CloudSyncItem> download(AuthenticatedUser actor, long afterVersion) {
        if (afterVersion < 0) throw new IllegalArgumentException("afterVersion must not be negative");
        List<CloudSyncItem> items = new ArrayList<>();
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
            "select event_id,event_type,payload_json,occurred_at,version from sync_events where user_id=? and version>? order by version limit 500")) {
            statement.setString(1, actor.id());
            statement.setLong(2, afterVersion);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) items.add(new CloudSyncItem(rows.getString(1), rows.getString(2), rows.getString(3), Instant.parse(rows.getString(4)), rows.getLong(5)));
            }
        } catch (SQLException error) { throw database(error); }
        return List.copyOf(items);
    }

    ClassAssignment createAssignment(AuthenticatedUser actor, String classroomId, String exerciseId, String title,
                                     String description, Instant dueAt, AssignmentStatus status) {
        requireTeacher(actor, classroomId);
        validateAssignmentDetails(exerciseId, title, description, dueAt);
        if (status != AssignmentStatus.DRAFT && status != AssignmentStatus.PUBLISHED) {
            throw new IllegalArgumentException("New assignments must be DRAFT or PUBLISHED");
        }
        String id = UUID.randomUUID().toString();
        Instant now = clock.instant();
        Instant publishedAt = status == AssignmentStatus.PUBLISHED ? now : null;
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
            "insert into class_assignments(id,classroom_id,exercise_id,title,description,created_at,status,"
                + "due_at,published_at,copied_from_assignment_id,version,updated_at) values(?,?,?,?,?,?,?,?,?,?,1,?)")) {
            statement.setString(1, id);
            statement.setString(2, classroomId);
            statement.setString(3, exerciseId.trim());
            statement.setString(4, title.trim());
            statement.setString(5, normalizeDescription(description));
            statement.setString(6, now.toString());
            statement.setString(7, status.name());
            statement.setString(8, dueAt == null ? null : dueAt.toString());
            statement.setString(9, publishedAt == null ? null : publishedAt.toString());
            statement.setString(10, null);
            statement.setString(11, now.toString());
            statement.executeUpdate();
            audit(connection, actor.id(), "ASSIGNMENT_CREATE", "ASSIGNMENT", id, "SUCCESS", status.name());
            return assignment(connection, classroomId, id);
        } catch (SQLException error) { throw database(error); }
    }

    ClassAssignment copyAssignment(AuthenticatedUser actor, String classroomId, String assignmentId,
                                   long expectedVersion) {
        requireTeacher(actor, classroomId);
        try (Connection connection = open()) {
            ClassAssignment source = assignment(connection, classroomId, assignmentId);
            requireVersion(source, expectedVersion);
            String id = UUID.randomUUID().toString();
            Instant now = clock.instant();
            Instant copiedDueAt = source.dueAt() != null && source.dueAt().isAfter(now) ? source.dueAt() : null;
            String copiedTitle = source.title().length() <= 153 ? source.title() + " (copy)" : source.title();
            try (PreparedStatement statement = connection.prepareStatement(
                "insert into class_assignments(id,classroom_id,exercise_id,title,description,created_at,status,"
                    + "due_at,published_at,copied_from_assignment_id,version,updated_at) "
                    + "values(?,?,?,?,?,?,'DRAFT',?,null,?,1,?)")) {
                statement.setString(1, id);
                statement.setString(2, classroomId);
                statement.setString(3, source.exerciseId());
                statement.setString(4, copiedTitle);
                statement.setString(5, source.description());
                statement.setString(6, now.toString());
                statement.setString(7, copiedDueAt == null ? null : copiedDueAt.toString());
                statement.setString(8, source.id());
                statement.setString(9, now.toString());
                statement.executeUpdate();
            }
            audit(connection, actor.id(), "ASSIGNMENT_COPY", "ASSIGNMENT", id, "SUCCESS", "SOURCE_VERSION_OK");
            return assignment(connection, classroomId, id);
        } catch (SQLException error) { throw database(error); }
    }

    ClassAssignment setAssignmentDueAt(AuthenticatedUser actor, String classroomId, String assignmentId,
                                       Instant dueAt, long expectedVersion) {
        requireTeacher(actor, classroomId);
        if (dueAt == null || !dueAt.isAfter(clock.instant())) {
            throw new IllegalArgumentException("dueAt must be in the future");
        }
        try (Connection connection = open()) {
            ClassAssignment current = assignment(connection, classroomId, assignmentId);
            requireEditable(current);
            requireVersion(current, expectedVersion);
            Instant now = clock.instant();
            try (PreparedStatement statement = connection.prepareStatement(
                "update class_assignments set due_at=?,updated_at=?,version=version+1 "
                    + "where id=? and classroom_id=? and version=?")) {
                statement.setString(1, dueAt.toString());
                statement.setString(2, now.toString());
                statement.setString(3, assignmentId);
                statement.setString(4, classroomId);
                statement.setLong(5, expectedVersion);
                requireUpdated(statement, connection, classroomId, assignmentId);
            }
            audit(connection, actor.id(), "ASSIGNMENT_DUE_UPDATE", "ASSIGNMENT", assignmentId,
                "SUCCESS", "VERSION_MATCHED");
            return assignment(connection, classroomId, assignmentId);
        } catch (SQLException error) { throw database(error); }
    }

    ClassAssignment changeAssignmentStatus(AuthenticatedUser actor, String classroomId, String assignmentId,
                                            AssignmentStatus status, long expectedVersion) {
        requireTeacher(actor, classroomId);
        if (status == null) throw new IllegalArgumentException("status must not be null");
        Instant now = clock.instant();
        try (Connection connection = open()) {
            ClassAssignment current = assignment(connection, classroomId, assignmentId);
            requireVersion(current, expectedVersion);
            if (!validTransition(current.status(), status)) {
                throw new IllegalArgumentException("Assignment status transition is not allowed");
            }
            String publishedAt = status == AssignmentStatus.PUBLISHED && current.publishedAt() == null
                ? now.toString() : current.publishedAt() == null ? null : current.publishedAt().toString();
            try (PreparedStatement statement = connection.prepareStatement(
                "update class_assignments set status=?,published_at=?,updated_at=?,version=version+1 "
                    + "where id=? and classroom_id=? and version=?")) {
                statement.setString(1, status.name());
                statement.setString(2, publishedAt);
                statement.setString(3, now.toString());
                statement.setString(4, assignmentId);
                statement.setString(5, classroomId);
                statement.setLong(6, expectedVersion);
                requireUpdated(statement, connection, classroomId, assignmentId);
            }
            audit(connection, actor.id(), "ASSIGNMENT_STATUS_UPDATE", "ASSIGNMENT", assignmentId,
                "SUCCESS", status.name());
            return assignment(connection, classroomId, assignmentId);
        } catch (SQLException error) { throw database(error); }
    }

    ClassAssignment updateAssignment(AuthenticatedUser actor, String classroomId, String assignmentId,
                                     String title, String description, Instant dueAt, long expectedVersion) {
        requireTeacher(actor, classroomId);
        validateAssignmentDetails("existing", title, description, dueAt);
        try (Connection connection = open()) {
            ClassAssignment current = assignment(connection, classroomId, assignmentId);
            requireEditable(current);
            requireVersion(current, expectedVersion);
            try (PreparedStatement statement = connection.prepareStatement(
                "update class_assignments set title=?,description=?,due_at=?,updated_at=?,version=version+1 "
                    + "where id=? and classroom_id=? and version=?")) {
                statement.setString(1, title.trim());
                statement.setString(2, normalizeDescription(description));
                statement.setString(3, dueAt == null ? null : dueAt.toString());
                statement.setString(4, clock.instant().toString());
                statement.setString(5, assignmentId);
                statement.setString(6, classroomId);
                statement.setLong(7, expectedVersion);
                requireUpdated(statement, connection, classroomId, assignmentId);
            }
            audit(connection, actor.id(), "ASSIGNMENT_DETAILS_UPDATE", "ASSIGNMENT", assignmentId,
                "SUCCESS", "VERSION_MATCHED");
            return assignment(connection, classroomId, assignmentId);
        } catch (SQLException error) { throw database(error); }
    }

    private boolean validTransition(AssignmentStatus from, AssignmentStatus to) {
        if (from == to) return true;
        return switch (from) {
            case DRAFT -> to == AssignmentStatus.PUBLISHED || to == AssignmentStatus.WITHDRAWN;
            case PUBLISHED -> to == AssignmentStatus.CLOSED || to == AssignmentStatus.WITHDRAWN;
            case CLOSED, WITHDRAWN -> to == AssignmentStatus.ARCHIVED;
            case ARCHIVED -> false;
        };
    }

    List<ClassAssignment> listAssignments(AuthenticatedUser actor, String classroomId,
                                          AssignmentStatus statusFilter) {
        requireMember(actor, classroomId);
        closeExpiredAssignments(classroomId);
        List<ClassAssignment> result = new ArrayList<>();
        boolean teacher = actor.hasRole(UserRole.ADMIN) || isTeacher(actor, classroomId);
        if (!teacher && statusFilter != null && statusFilter != AssignmentStatus.PUBLISHED
            && statusFilter != AssignmentStatus.CLOSED) {
            throw new SecurityException("Students cannot view unpublished assignments");
        }
        StringBuilder sql = new StringBuilder("select ").append(ASSIGNMENT_COLUMNS)
            .append(" from class_assignments where classroom_id=?");
        if (!teacher) sql.append(" and status in ('PUBLISHED','CLOSED')");
        if (statusFilter != null) sql.append(" and status=?");
        sql.append(" order by created_at desc");
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            statement.setString(1, classroomId);
            if (statusFilter != null) statement.setString(2, statusFilter.name());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(assignment(rows, classroomId));
            }
            return List.copyOf(result);
        } catch (SQLException error) { throw database(error); }
    }

    private void closeExpiredAssignments(String classroomId) {
        Instant now = clock.instant();
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
            "update class_assignments set status='CLOSED',updated_at=?,version=version+1 "
                + "where classroom_id=? and status='PUBLISHED' and due_at is not null and due_at<=?")) {
            statement.setString(1, now.toString());
            statement.setString(2, classroomId);
            statement.setString(3, now.toString());
            statement.executeUpdate();
        } catch (SQLException error) { throw database(error); }
    }

    AssignmentSubmission submitAssignment(AuthenticatedUser actor, String classroomId, String assignmentId,
                                          AssignmentSubmissionRequest request) {
        requireStudent(actor, classroomId);
        closeExpiredAssignments(classroomId);
        try (Connection connection = open()) {
            ClassAssignment assignment = assignment(connection, classroomId, assignmentId);
            ensureSubmissionOpen(assignment);
            AssignmentSubmission existing = submissionByOperation(connection, actor.id(), request.operationId());
            if (existing != null) {
                if (!existing.assignmentId().equals(assignmentId)) {
                    throw new SubmissionOperationConflictException();
                }
                return existing;
            }
            String id = UUID.randomUUID().toString();
            Instant submittedAt = clock.instant();
            try (PreparedStatement statement = connection.prepareStatement(
                "insert into assignment_submissions(id,operation_id,classroom_id,assignment_id,user_id,"
                    + "attempt_number,status,result_hash,error_code,client_completed_at,submitted_at) "
                    + "values(?,?,?,?,?,(select coalesce(max(attempt_number),0)+1 from assignment_submissions "
                    + "where assignment_id=? and user_id=?),?,?,?,?,?) "
                    + "on conflict(user_id,operation_id) do nothing")) {
                statement.setString(1, id);
                statement.setString(2, request.operationId());
                statement.setString(3, classroomId);
                statement.setString(4, assignmentId);
                statement.setString(5, actor.id());
                statement.setString(6, assignmentId);
                statement.setString(7, actor.id());
                statement.setString(8, request.passed()
                    ? AssignmentSubmissionStatus.PASSED.name() : AssignmentSubmissionStatus.FAILED.name());
                statement.setString(9, request.resultHash());
                statement.setString(10, request.errorCode());
                statement.setString(11, request.clientCompletedAt() == null
                    ? null : request.clientCompletedAt().toString());
                statement.setString(12, submittedAt.toString());
                if (statement.executeUpdate() == 0) {
                    AssignmentSubmission concurrent = submissionByOperation(
                        connection, actor.id(), request.operationId());
                    if (concurrent == null || !concurrent.assignmentId().equals(assignmentId)) {
                        throw new SubmissionOperationConflictException();
                    }
                    return concurrent;
                }
            }
            return submissionById(connection, id);
        } catch (SQLException error) { throw database(error); }
    }

    List<AssignmentSubmission> listOwnSubmissions(AuthenticatedUser actor, String classroomId,
                                                  String assignmentId) {
        requireStudent(actor, classroomId);
        try (Connection connection = open()) {
            assignment(connection, classroomId, assignmentId);
            List<AssignmentSubmission> submissions = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                "select " + SUBMISSION_COLUMNS + " from assignment_submissions "
                    + "where classroom_id=? and assignment_id=? and user_id=? order by attempt_number")) {
                statement.setString(1, classroomId);
                statement.setString(2, assignmentId);
                statement.setString(3, actor.id());
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) submissions.add(submission(rows));
                }
            }
            return List.copyOf(submissions);
        } catch (SQLException error) { throw database(error); }
    }

    /**
     * W6.2 batch read: for one classroom, whether the current student has a PASSED
     * submission per assignment, in a single query (removes the queue's N+1).
     */
    Map<String, Boolean> listOwnPassedStatuses(AuthenticatedUser actor, String classroomId) {
        requireStudent(actor, classroomId);
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(
                 "select assignment_id, max(case when status='PASSED' then 1 else 0 end) as passed "
                     + "from assignment_submissions where classroom_id=? and user_id=? "
                     + "group by assignment_id")) {
            statement.setString(1, classroomId);
            statement.setString(2, actor.id());
            Map<String, Boolean> result = new LinkedHashMap<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.put(rows.getString("assignment_id"), rows.getInt("passed") == 1);
                }
            }
            return Map.copyOf(result);
        } catch (SQLException error) { throw database(error); }
    }

    private void ensureSubmissionOpen(ClassAssignment assignment) {
        if (assignment.status() == AssignmentStatus.PUBLISHED
            && (assignment.dueAt() == null || assignment.dueAt().isAfter(clock.instant()))) return;
        String code = switch (assignment.status()) {
            case DRAFT -> "ASSIGNMENT_NOT_PUBLISHED";
            case PUBLISHED, CLOSED -> "ASSIGNMENT_CLOSED";
            case WITHDRAWN -> "ASSIGNMENT_WITHDRAWN";
            case ARCHIVED -> "ASSIGNMENT_ARCHIVED";
        };
        throw new AssignmentSubmissionRejectedException(code, "Assignment does not accept submissions");
    }

    private AssignmentSubmission submissionByOperation(Connection connection, String userId, String operationId)
        throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "select " + SUBMISSION_COLUMNS + " from assignment_submissions where user_id=? and operation_id=?")) {
            statement.setString(1, userId);
            statement.setString(2, operationId);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? submission(row) : null;
            }
        }
    }

    private AssignmentSubmission submissionById(Connection connection, String id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "select " + SUBMISSION_COLUMNS + " from assignment_submissions where id=?")) {
            statement.setString(1, id);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new IllegalStateException("Submission insert did not return a row");
                return submission(row);
            }
        }
    }

    private AssignmentSubmission submission(ResultSet row) throws SQLException {
        String clientCompletedAt = row.getString("client_completed_at");
        return new AssignmentSubmission(
            row.getString("id"), row.getString("operation_id"), row.getString("classroom_id"),
            row.getString("assignment_id"), row.getString("user_id"), row.getInt("attempt_number"),
            AssignmentSubmissionStatus.valueOf(row.getString("status")), row.getString("result_hash"),
            row.getString("error_code"), clientCompletedAt == null ? null : Instant.parse(clientCompletedAt),
            Instant.parse(row.getString("submitted_at"))
        );
    }

    AssignmentAnalyticsReport assignmentAnalytics(AuthenticatedUser actor, String classroomId,
                                                   String assignmentId, AssignmentAnalyticsFilter filter) {
        requireTeacher(actor, classroomId);
        AssignmentAnalyticsFilter applied = filter == null ? AssignmentAnalyticsFilter.firstPage() : filter;
        try (Connection connection = open()) {
            assignment(connection, classroomId, assignmentId);
            List<AssignmentAnalyticsRow> allRows = assignmentAnalyticsRows(
                connection, classroomId, assignmentId, applied);
            List<AssignmentAnalyticsRow> filteredRows = allRows.stream()
                .filter(row -> matchesStatus(row, applied.status())).toList();
            int submittedStudents = (int) allRows.stream().filter(row -> row.attemptCount() > 0).count();
            int passedStudents = (int) allRows.stream().filter(row -> row.passedAttempts() > 0).count();
            int totalAttempts = allRows.stream().mapToInt(AssignmentAnalyticsRow::attemptCount).sum();
            long offsetValue = (long) applied.page() * applied.pageSize();
            int fromIndex = (int) Math.min(offsetValue, filteredRows.size());
            int toIndex = Math.min(fromIndex + applied.pageSize(), filteredRows.size());
            return new AssignmentAnalyticsReport(
                classroomId, assignmentId, allRows.size(), submittedStudents, passedStudents, totalAttempts,
                rate(submittedStudents, allRows.size()), rate(passedStudents, submittedStudents),
                commonErrors(connection, classroomId, assignmentId, applied),
                filteredRows.subList(fromIndex, toIndex), applied.page(), applied.pageSize(), filteredRows.size(),
                clock.instant()
            );
        } catch (SQLException error) { throw database(error); }
    }

    String exportAssignmentAnalyticsCsv(AuthenticatedUser actor, String classroomId, String assignmentId,
                                        AssignmentAnalyticsFilter filter) {
        requireTeacher(actor, classroomId);
        AssignmentAnalyticsFilter applied = filter == null ? AssignmentAnalyticsFilter.firstPage() : filter;
        try (Connection connection = open()) {
            assignment(connection, classroomId, assignmentId);
            List<AssignmentAnalyticsRow> rows = assignmentAnalyticsRows(connection, classroomId, assignmentId, applied)
                .stream().filter(row -> matchesStatus(row, applied.status())).toList();
            StringBuilder csv = new StringBuilder(
                "\uFEFFstudent_email,display_name,status,attempt_count,passed_attempts,last_submitted_at\r\n");
            for (AssignmentAnalyticsRow row : rows) {
                csv.append(csvCell(row.email())).append(',').append(csvCell(row.displayName())).append(',')
                    .append(row.status().name()).append(',').append(row.attemptCount()).append(',')
                    .append(row.passedAttempts()).append(',')
                    .append(row.lastSubmittedAt() == null ? "" : row.lastSubmittedAt()).append("\r\n");
            }
            try (PreparedStatement audit = connection.prepareStatement(
                "insert into export_audit(id,user_id,classroom_id,row_count,created_at,assignment_id,"
                    + "export_type,filter_summary) values(?,?,?,?,?,?,?,?)")) {
                audit.setString(1, UUID.randomUUID().toString());
                audit.setString(2, actor.id());
                audit.setString(3, classroomId);
                audit.setInt(4, rows.size());
                audit.setString(5, clock.instant().toString());
                audit.setString(6, assignmentId);
                audit.setString(7, "ASSIGNMENT_ANALYTICS");
                audit.setString(8, analyticsFilterSummary(applied));
                audit.executeUpdate();
            }
            audit(connection, actor.id(), "ASSIGNMENT_ANALYTICS_EXPORT", "ASSIGNMENT", assignmentId,
                "SUCCESS", "FILTERED_EXPORT");
            return csv.toString();
        } catch (SQLException error) { throw database(error); }
    }

    private List<AssignmentAnalyticsRow> assignmentAnalyticsRows(Connection connection, String classroomId,
                                                                 String assignmentId,
                                                                 AssignmentAnalyticsFilter filter)
        throws SQLException {
        StringBuilder sql = new StringBuilder(
            "select u.id,u.email,u.display_name,count(s.id) attempt_count,"
                + "coalesce(sum(case when s.status='PASSED' then 1 else 0 end),0) passed_attempts,"
                + "max(s.submitted_at) last_submitted_at from classroom_members m "
                + "join users u on u.id=m.user_id left join assignment_submissions s "
                + "on s.assignment_id=? and s.user_id=m.user_id");
        if (filter.from() != null) sql.append(" and s.submitted_at>=?");
        if (filter.to() != null) sql.append(" and s.submitted_at<=?");
        sql.append(" where m.classroom_id=? and m.role='STUDENT' "
            + "group by u.id,u.email,u.display_name order by u.email");
        List<AssignmentAnalyticsRow> rows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            int parameter = 1;
            statement.setString(parameter++, assignmentId);
            if (filter.from() != null) statement.setString(parameter++, filter.from().toString());
            if (filter.to() != null) statement.setString(parameter++, filter.to().toString());
            statement.setString(parameter, classroomId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    int attempts = result.getInt("attempt_count");
                    int passedAttempts = result.getInt("passed_attempts");
                    String lastSubmittedAt = result.getString("last_submitted_at");
                    AssignmentStudentStatus status = attempts == 0 ? AssignmentStudentStatus.NOT_SUBMITTED
                        : passedAttempts > 0 ? AssignmentStudentStatus.PASSED : AssignmentStudentStatus.FAILED;
                    rows.add(new AssignmentAnalyticsRow(
                        result.getString("id"), result.getString("email"), result.getString("display_name"),
                        status, attempts, passedAttempts,
                        lastSubmittedAt == null ? null : Instant.parse(lastSubmittedAt)
                    ));
                }
            }
        }
        return List.copyOf(rows);
    }

    private List<AssignmentErrorCount> commonErrors(Connection connection, String classroomId,
                                                    String assignmentId, AssignmentAnalyticsFilter filter)
        throws SQLException {
        StringBuilder sql = new StringBuilder(
            "select s.error_code,count(*) error_count from assignment_submissions s "
                + "join classroom_members m on m.classroom_id=s.classroom_id and m.user_id=s.user_id "
                + "where s.classroom_id=? and s.assignment_id=? and s.status='FAILED' and s.error_code is not null");
        if (filter.from() != null) sql.append(" and s.submitted_at>=?");
        if (filter.to() != null) sql.append(" and s.submitted_at<=?");
        sql.append(" group by s.error_code order by error_count desc,s.error_code limit 10");
        List<AssignmentErrorCount> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            int parameter = 1;
            statement.setString(parameter++, classroomId);
            statement.setString(parameter++, assignmentId);
            if (filter.from() != null) statement.setString(parameter++, filter.from().toString());
            if (filter.to() != null) statement.setString(parameter, filter.to().toString());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(new AssignmentErrorCount(rows.getString("error_code"), rows.getInt("error_count")));
                }
            }
        }
        return List.copyOf(result);
    }

    private boolean matchesStatus(AssignmentAnalyticsRow row, AssignmentStudentStatus filter) {
        if (filter == null) return true;
        if (filter == AssignmentStudentStatus.SUBMITTED) return row.attemptCount() > 0;
        return row.status() == filter;
    }

    private double rate(int numerator, int denominator) {
        return denominator == 0 ? 0.0 : (double) numerator / denominator;
    }

    private String analyticsFilterSummary(AssignmentAnalyticsFilter filter) {
        return "status=" + (filter.status() == null ? "ALL" : filter.status().name())
            + ";from=" + (filter.from() == null ? "" : filter.from())
            + ";to=" + (filter.to() == null ? "" : filter.to());
    }

    ClassLearningSummary classLearningSummary(AuthenticatedUser actor, String classroomId) {
        requireTeacher(actor, classroomId);
        java.util.Set<String> seenStudents = new java.util.HashSet<>();
        java.util.Set<String> activeStudents = new java.util.HashSet<>();
        int events = 0;
        int success = 0;
        int unreadablePayloads = 0;
        try (Connection c = open(); PreparedStatement s = c.prepareStatement(
            "select m.user_id,e.payload_json from classroom_members m "
            + "left join sync_events e on e.user_id=m.user_id "
            + "where m.classroom_id=? and m.role='STUDENT'")) {
            s.setString(1, classroomId);
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    String userId = r.getString(1);
                    seenStudents.add(userId);
                    String payload = r.getString(2);
                    if (payload == null) continue;
                    events++;
                    activeStudents.add(userId);
                    try {
                        if (JSON.readTree(payload).path("successful").asBoolean(false)) success++;
                    } catch (IOException error) {
                        unreadablePayloads++;
                    }
                }
            }
        } catch (SQLException e) {
            throw database(e);
        }
        if (unreadablePayloads > 0) {
            log.warn("class learning summary for {}: skipped {} sync events with unreadable payloads", classroomId, unreadablePayloads);
        }
        return new ClassLearningSummary(
            classroomId, seenStudents.size(), activeStudents.size(), events, success, clock.instant());
    }

    String exportClassLearningCsv(AuthenticatedUser actor, String classroomId) {
        requireTeacher(actor, classroomId);
        StringBuilder csv = new StringBuilder("\uFEFFstudent_email,event_type,occurred_at,successful\r\n");
        int rows = 0;
        int unreadablePayloads = 0;
        try (Connection c = open(); PreparedStatement s = c.prepareStatement(
            "select u.email,e.event_type,e.occurred_at,e.payload_json from classroom_members m "
            + "join users u on u.id=m.user_id join sync_events e on e.user_id=m.user_id "
            + "where m.classroom_id=? and m.role='STUDENT' order by e.occurred_at")) {
            s.setString(1, classroomId);
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    boolean successful = false;
                    try {
                        successful = JSON.readTree(r.getString(4)).path("successful").asBoolean(false);
                    } catch (IOException error) {
                        unreadablePayloads++;
                    }
                    csv.append(csvCell(r.getString(1))).append(',').append(csvCell(r.getString(2))).append(',')
                        .append(csvCell(r.getString(3))).append(',').append(successful).append("\r\n");
                    rows++;
                }
            }
            try (PreparedStatement audit = c.prepareStatement(
                "insert into export_audit(id,user_id,classroom_id,row_count,created_at) values(?,?,?,?,?)")) {
                audit.setString(1, UUID.randomUUID().toString());
                audit.setString(2, actor.id());
                audit.setString(3, classroomId);
                audit.setInt(4, rows);
                audit.setString(5, clock.instant().toString());
                audit.executeUpdate();
            }
        } catch (SQLException e) {
            throw database(e);
        }
        if (unreadablePayloads > 0) {
            log.warn("class learning CSV export for {}: {} of {} rows had unreadable payloads", classroomId, unreadablePayloads, rows);
        }
        return csv.toString();
    }

    private String csvCell(String value) {
        String normalized = value == null ? "" : value;
        if (!normalized.isEmpty() && "=+-@".indexOf(normalized.charAt(0)) >= 0) normalized = "'" + normalized;
        return "\"" + normalized.replace("\"", "\"\"") + "\"";
    }

    private ClassAssignment assignment(Connection connection, String classroomId, String assignmentId)
        throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "select " + ASSIGNMENT_COLUMNS + " from class_assignments where id=? and classroom_id=?")) {
            statement.setString(1, assignmentId);
            statement.setString(2, classroomId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new IllegalArgumentException("Assignment was not found");
                return assignment(row, classroomId);
            }
        }
    }

    private ClassAssignment assignment(ResultSet row, String classroomId) throws SQLException {
        String dueAt = row.getString("due_at");
        String publishedAt = row.getString("published_at");
        return new ClassAssignment(
            row.getString("id"), classroomId, row.getString("exercise_id"), row.getString("title"),
            Instant.parse(row.getString("created_at")), AssignmentStatus.valueOf(row.getString("status")),
            dueAt == null ? null : Instant.parse(dueAt), Instant.parse(row.getString("updated_at")),
            row.getString("description"), publishedAt == null ? null : Instant.parse(publishedAt),
            row.getString("copied_from_assignment_id"), row.getLong("version")
        );
    }

    private void validateAssignmentDetails(String exerciseId, String title, String description, Instant dueAt) {
        if (exerciseId == null || exerciseId.isBlank()) {
            throw new IllegalArgumentException("exerciseId must not be blank");
        }
        if (title == null || title.isBlank() || title.length() > 160) {
            throw new IllegalArgumentException("title must be 1 to 160 characters");
        }
        if (description != null && description.length() > 2_000) {
            throw new IllegalArgumentException("description must contain at most 2000 characters");
        }
        if (dueAt != null && !dueAt.isAfter(clock.instant())) {
            throw new IllegalArgumentException("dueAt must be in the future");
        }
    }

    private String normalizeDescription(String description) {
        return description == null ? "" : description.trim();
    }

    private void requireEditable(ClassAssignment assignment) {
        if (assignment.status() == AssignmentStatus.ARCHIVED) {
            throw new IllegalArgumentException("Archived assignments cannot be edited");
        }
    }

    private void requireVersion(ClassAssignment assignment, long expectedVersion) {
        if (expectedVersion < 1) throw new IllegalArgumentException("expectedVersion must be positive");
        if (assignment.version() != expectedVersion) throw new AssignmentVersionConflictException(assignment);
    }

    private void requireUpdated(PreparedStatement statement, Connection connection, String classroomId,
                                String assignmentId) throws SQLException {
        if (statement.executeUpdate() != 1) {
            throw new AssignmentVersionConflictException(assignment(connection, classroomId, assignmentId));
        }
    }

    List<ClassroomService.RosterMember> classRoster(AuthenticatedUser actor, String classroomId) {
        requireTeacher(actor, classroomId);
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(
                 "select m.user_id, u.email, u.display_name, m.role from classroom_members m "
                     + "join users u on u.id = m.user_id where m.classroom_id=? "
                     + "order by case m.role when 'TEACHER' then 0 else 1 end, u.display_name")) {
            statement.setString(1, classroomId);
            List<ClassroomService.RosterMember> members = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    members.add(new ClassroomService.RosterMember(
                        rows.getString(1), rows.getString(2), rows.getString(3), UserRole.valueOf(rows.getString(4))));
                }
            }
            return members;
        } catch (SQLException error) {
            throw database(error);
        }
    }

    private Classroom classroom(String id) {
        try (Connection connection = open();
             PreparedStatement classroomStatement = connection.prepareStatement(
                 "select name, created_at from classrooms where id=?")) {
            classroomStatement.setString(1, id);
            try (ResultSet classroom = classroomStatement.executeQuery()) {
                if (!classroom.next()) {
                    throw new IllegalArgumentException("Classroom not found");
                }
                List<Member> members = new ArrayList<>();
                try (PreparedStatement memberStatement = connection.prepareStatement(
                    "select user_id, role from classroom_members where classroom_id=?")) {
                    memberStatement.setString(1, id);
                    try (ResultSet memberRows = memberStatement.executeQuery()) {
                        while (memberRows.next()) {
                            members.add(new Member(memberRows.getString(1), UserRole.valueOf(memberRows.getString(2))));
                        }
                    }
                }
                return new Classroom(id, classroom.getString(1), Instant.parse(classroom.getString(2)), members);
            }
        } catch (SQLException error) {
            throw database(error);
        }
    }

    private void requireTeacher(AuthenticatedUser actor, String classId) {
        if (actor.hasRole(UserRole.ADMIN)) return;
        try (Connection c = open();
             PreparedStatement s = c.prepareStatement("select 1 from classroom_members where classroom_id=? and user_id=? and role='TEACHER'")) {
            s.setString(1, classId);
            s.setString(2, actor.id());
            try (ResultSet r = s.executeQuery()) {
                if (!r.next()) throw new SecurityException("not classroom teacher");
            }
        } catch (SQLException e) {
            throw database(e);
        }
    }

    private boolean isTeacher(AuthenticatedUser actor, String classId) {
        if (actor.hasRole(UserRole.ADMIN)) return true;
        try (Connection c = open();
             PreparedStatement s = c.prepareStatement("select 1 from classroom_members where classroom_id=? and user_id=? and role='TEACHER'")) {
            s.setString(1, classId);
            s.setString(2, actor.id());
            try (ResultSet r = s.executeQuery()) {
                return r.next();
            }
        } catch (SQLException e) {
            throw database(e);
        }
    }

    private void requireMember(AuthenticatedUser actor, String classId) {
        if (actor.hasRole(UserRole.ADMIN)) return;
        try (Connection c = open();
             PreparedStatement s = c.prepareStatement("select 1 from classroom_members where classroom_id=? and user_id=?")) {
            s.setString(1, classId);
            s.setString(2, actor.id());
            try (ResultSet r = s.executeQuery()) {
                if (!r.next()) throw new SecurityException("not classroom member");
            }
        } catch (SQLException e) {
            throw database(e);
        }
    }

    private void requireStudent(AuthenticatedUser actor, String classId) {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
            "select 1 from classroom_members where classroom_id=? and user_id=? and role='STUDENT'")) {
            statement.setString(1, classId);
            statement.setString(2, actor.id());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new SecurityException("student classroom membership required");
            }
        } catch (SQLException error) { throw database(error); }
    }
}

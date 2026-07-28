package com.sqlteacher.infrastructure.cloud;

import com.sqlteacher.application.collaboration.AssignmentDeliveryResult;
import com.sqlteacher.application.collaboration.AssignmentDeliveryService;
import com.sqlteacher.application.collaboration.AssignmentSubmissionRequest;
import com.sqlteacher.application.collaboration.AssignmentSubmissionStatus;
import com.sqlteacher.application.collaboration.CloudApiClient;
import com.sqlteacher.application.collaboration.CloudAuthenticationService;
import com.sqlteacher.application.collaboration.CloudSessionService;
import com.sqlteacher.application.collaboration.CloudApiRequestException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Persists deterministic assignment summaries before retrying failed cloud delivery. */
public final class JdbcAssignmentDeliveryService implements AssignmentDeliveryService {
    private final CloudApiClient api;
    private final CloudSessionService sessions;
    private final Path database;

    public JdbcAssignmentDeliveryService(CloudApiClient api, CloudSessionService sessions, Path database) {
        this.api = Objects.requireNonNull(api);
        this.sessions = Objects.requireNonNull(sessions);
        this.database = Objects.requireNonNull(database).toAbsolutePath().normalize();
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException error) {
            throw new IllegalStateException("SQLite JDBC driver is unavailable", error);
        }
    }

    @Override
    public AssignmentDeliveryResult deliver(String classroomId, String assignmentId, boolean passed,
                                             String errorCode, Instant completedAt) {
        CloudAuthenticationService.Session session = currentSession();
        String operationId = UUID.randomUUID().toString();
        Instant completed = Objects.requireNonNull(completedAt, "completedAt must not be null");
        String normalizedError = normalizeErrorCode(errorCode);
        String resultHash = resultHash(classroomId, assignmentId, passed, normalizedError);
        PendingSubmission pending = new PendingSubmission(operationId, session.user().id(), classroomId,
            assignmentId, passed, resultHash, normalizedError, completed, 0);
        try {
            var delivered = api.submitAssignment(session.accessToken(), classroomId, assignmentId,
                request(pending));
            return new AssignmentDeliveryResult(operationId,
                delivered.status() == AssignmentSubmissionStatus.PASSED
                    ? AssignmentDeliveryResult.Status.PASSED : AssignmentDeliveryResult.Status.SUBMITTED,
                delivered.attemptNumber());
        } catch (RuntimeException error) {
            if (!retryable(error)) {
                return new AssignmentDeliveryResult(operationId, AssignmentDeliveryResult.Status.REJECTED, 0);
            }
            enqueue(pending, error);
            return new AssignmentDeliveryResult(operationId, AssignmentDeliveryResult.Status.QUEUED, 0);
        }
    }

    @Override
    public RetrySummary retryPending() {
        CloudAuthenticationService.Session session = currentSession();
        List<PendingSubmission> pending = pending(session.user().id(), Instant.now());
        int delivered = 0;
        int rejected = 0;
        for (PendingSubmission item : pending) {
            try {
                api.submitAssignment(session.accessToken(), item.classroomId(), item.assignmentId(), request(item));
                markDelivered(item.operationId(), session.user().id());
                delivered++;
            } catch (RuntimeException error) {
                if (retryable(error)) {
                    postpone(item, error);
                } else {
                    markRejected(item.operationId(), session.user().id(), error);
                    rejected++;
                }
            }
        }
        return new RetrySummary(pending.size(), delivered, rejected, pendingCount());
    }

    @Override
    public int pendingCount() {
        String accountId = currentSession().user().id();
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
            "select count(*) from assignment_submission_queue where account_id=? and status='QUEUED'")) {
            statement.setString(1, accountId);
            try (ResultSet row = statement.executeQuery()) { return row.getInt(1); }
        } catch (SQLException error) {
            throw database(error);
        }
    }

    private void enqueue(PendingSubmission item, RuntimeException error) {
        Instant now = Instant.now();
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
            "insert into assignment_submission_queue(operation_id,account_id,classroom_id,assignment_id,passed,"
                + "result_hash,error_code,client_completed_at,status,retry_count,next_retry_at,last_error_type,"
                + "created_at,updated_at) values(?,?,?,?,?,?,?,?,'QUEUED',0,?,?,?,?)")) {
            bindPending(statement, item);
            statement.setString(9, now.toString());
            statement.setString(10, error.getClass().getSimpleName());
            statement.setString(11, now.toString());
            statement.setString(12, now.toString());
            statement.executeUpdate();
        } catch (SQLException sqlError) {
            throw database(sqlError);
        }
    }

    private List<PendingSubmission> pending(String accountId, Instant now) {
        List<PendingSubmission> result = new ArrayList<>();
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
            "select operation_id,account_id,classroom_id,assignment_id,passed,result_hash,error_code,"
                + "client_completed_at,retry_count from assignment_submission_queue "
                + "where account_id=? and status='QUEUED' and next_retry_at<=? order by created_at limit 50")) {
            statement.setString(1, accountId);
            statement.setString(2, now.toString());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(new PendingSubmission(
                    rows.getString("operation_id"), rows.getString("account_id"), rows.getString("classroom_id"),
                    rows.getString("assignment_id"), rows.getInt("passed") != 0, rows.getString("result_hash"),
                    rows.getString("error_code"), Instant.parse(rows.getString("client_completed_at")),
                    rows.getInt("retry_count")));
            }
            return List.copyOf(result);
        } catch (SQLException error) {
            throw database(error);
        }
    }

    private void markDelivered(String operationId, String accountId) {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
            "update assignment_submission_queue set status='DELIVERED',last_error_type=null,updated_at=? "
                + "where operation_id=? and account_id=?")) {
            statement.setString(1, Instant.now().toString());
            statement.setString(2, operationId);
            statement.setString(3, accountId);
            statement.executeUpdate();
        } catch (SQLException error) { throw database(error); }
    }

    private void postpone(PendingSubmission item, RuntimeException error) {
        int retryCount = item.retryCount() + 1;
        long delayMinutes = Math.min(60, 1L << Math.min(retryCount, 6));
        Instant now = Instant.now();
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
            "update assignment_submission_queue set retry_count=?,next_retry_at=?,last_error_type=?,updated_at=? "
                + "where operation_id=? and account_id=? and status='QUEUED'")) {
            statement.setInt(1, retryCount);
            statement.setString(2, now.plus(delayMinutes, ChronoUnit.MINUTES).toString());
            statement.setString(3, error.getClass().getSimpleName());
            statement.setString(4, now.toString());
            statement.setString(5, item.operationId());
            statement.setString(6, item.accountId());
            statement.executeUpdate();
        } catch (SQLException sqlError) { throw database(sqlError); }
    }

    private void markRejected(String operationId, String accountId, RuntimeException error) {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
            "update assignment_submission_queue set status='REJECTED',last_error_type=?,updated_at=? "
                + "where operation_id=? and account_id=? and status='QUEUED'")) {
            statement.setString(1, error instanceof CloudApiRequestException cloud ? cloud.code()
                : error.getClass().getSimpleName());
            statement.setString(2, Instant.now().toString());
            statement.setString(3, operationId);
            statement.setString(4, accountId);
            statement.executeUpdate();
        } catch (SQLException sqlError) { throw database(sqlError); }
    }

    private boolean retryable(RuntimeException error) {
        return !(error instanceof CloudApiRequestException cloud) || cloud.retryable();
    }

    private void bindPending(PreparedStatement statement, PendingSubmission item) throws SQLException {
        statement.setString(1, item.operationId());
        statement.setString(2, item.accountId());
        statement.setString(3, item.classroomId());
        statement.setString(4, item.assignmentId());
        statement.setInt(5, item.passed() ? 1 : 0);
        statement.setString(6, item.resultHash());
        statement.setString(7, item.errorCode());
        statement.setString(8, item.completedAt().toString());
    }

    private AssignmentSubmissionRequest request(PendingSubmission item) {
        return new AssignmentSubmissionRequest(item.operationId(), item.passed(), item.resultHash(),
            item.errorCode(), item.completedAt());
    }

    private CloudAuthenticationService.Session currentSession() {
        return sessions.current().orElseThrow(() -> new IllegalStateException("Cloud login is required"));
    }

    private Connection open() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + database);
    }

    private String resultHash(String classroomId, String assignmentId, boolean passed, String errorCode) {
        String canonical = "v1\n" + requireId(classroomId, "classroomId") + "\n"
            + requireId(assignmentId, "assignmentId") + "\n" + passed + "\n"
            + (errorCode == null ? "" : errorCode);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private String requireId(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }

    private String normalizeErrorCode(String errorCode) {
        if (errorCode == null || errorCode.isBlank()) return null;
        if (!errorCode.matches("[A-Z0-9_]{2,64}")) throw new IllegalArgumentException("errorCode is invalid");
        return errorCode;
    }

    private IllegalStateException database(SQLException error) {
        return new IllegalStateException("Assignment submission queue operation failed", error);
    }

    private record PendingSubmission(String operationId, String accountId, String classroomId,
                                     String assignmentId, boolean passed, String resultHash, String errorCode,
                                     Instant completedAt, int retryCount) { }
}

package com.sqlteacher.infrastructure.cloud;

import com.sqlteacher.application.collaboration.AssignmentAnalyticsFilter;
import com.sqlteacher.application.collaboration.AssignmentAnalyticsRow;
import com.sqlteacher.application.collaboration.AssignmentStatus;
import com.sqlteacher.application.collaboration.AssignmentStudentStatus;
import com.sqlteacher.application.collaboration.ClassAssignment;
import com.sqlteacher.application.collaboration.ClassroomService;
import com.sqlteacher.application.collaboration.CloudApiClient;
import com.sqlteacher.application.collaboration.CloudAuthenticationService;
import com.sqlteacher.application.collaboration.CloudSessionService;
import com.sqlteacher.application.collaboration.UserRole;
import com.sqlteacher.application.learning.DiagnosisReasonCode;
import com.sqlteacher.application.learning.InterventionCandidate;
import com.sqlteacher.application.learning.InterventionService;
import com.sqlteacher.application.learning.InterventionStatus;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Builds a teacher work queue only from class-scoped APIs that already enforce authorization. */
public final class DefaultInterventionService implements InterventionService {
    private final CloudApiClient api;
    private final CloudSessionService sessions;
    private final Path databasePath;
    private final Clock clock;

    public DefaultInterventionService(CloudApiClient api, CloudSessionService sessions, Path databasePath) {
        this(api, sessions, databasePath, Clock.systemUTC());
    }

    DefaultInterventionService(CloudApiClient api, CloudSessionService sessions, Path databasePath, Clock clock) {
        this.api = Objects.requireNonNull(api); this.sessions = Objects.requireNonNull(sessions);
        this.databasePath = Objects.requireNonNull(databasePath); this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public List<InterventionCandidate> refreshAuthorized() {
        CloudAuthenticationService.Session session = sessions.current()
            .orElseThrow(() -> new IllegalStateException("请先登录云端账号"));
        String token = session.accessToken();
        Instant now = clock.instant();
        List<InterventionCandidate> result = new ArrayList<>();
        for (ClassroomService.Classroom classroom : api.listClasses(token)) {
            boolean teacher = classroom.members().stream().anyMatch(member ->
                member.userId().equals(session.user().id()) && member.role() == UserRole.TEACHER);
            if (!teacher && !session.user().hasRole(UserRole.ADMIN)) continue;
            for (ClassAssignment assignment : api.listAssignments(token, classroom.id())) {
                if (assignment.status() != AssignmentStatus.PUBLISHED) continue;
                var report = api.getAssignmentAnalytics(token, classroom.id(), assignment.id(),
                    new AssignmentAnalyticsFilter(null, null, null, 0, 200));
                for (AssignmentAnalyticsRow row : report.rows()) {
                    CandidateRule rule = classify(assignment, row, now);
                    if (rule == null) continue;
                    String id = stableId(classroom.id(), assignment.id(), row.userId(), rule.reason(),
                        row.attemptCount(), row.lastSubmittedAt());
                    result.add(new InterventionCandidate(id, classroom.id(), classroom.name(), assignment.id(),
                        assignment.title(), row.userId(), displayName(row), rule.reason(), rule.summary(),
                        rule.priority(), loadStatus(id), now));
                }
            }
        }
        return result.stream().filter(item -> item.status() != InterventionStatus.RESOLVED
                && item.status() != InterventionStatus.DISMISSED)
            .sorted(Comparator.comparingInt(InterventionCandidate::priority).reversed()
                .thenComparing(InterventionCandidate::classroomName)
                .thenComparing(InterventionCandidate::studentDisplayName)).toList();
    }

    @Override
    public void updateStatus(String candidateId, InterventionStatus status) {
        if (candidateId == null || candidateId.isBlank()) throw new IllegalArgumentException("candidateId must not be blank");
        Objects.requireNonNull(status);
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement("""
            insert into intervention_state(candidate_id,status,updated_at) values(?,?,?)
            on conflict(candidate_id) do update set status=excluded.status,updated_at=excluded.updated_at
            """)) {
            statement.setString(1, candidateId.trim()); statement.setString(2, status.name());
            statement.setString(3, clock.instant().toString()); statement.executeUpdate();
        } catch (SQLException error) {
            throw new IllegalStateException("无法更新干预状态", error);
        }
    }

    @Override
    public String exportCsv(List<InterventionCandidate> candidates) {
        StringBuilder csv = new StringBuilder("\ufeff班级,任务,学生,原因,证据,状态,更新时间(UTC)\r\n");
        for (InterventionCandidate item : candidates == null ? List.<InterventionCandidate>of() : candidates) {
            row(csv, item.classroomName(), item.assignmentTitle(), item.studentDisplayName(), item.reason().name(),
                item.evidenceSummary(), item.status().name(), item.updatedAt());
        }
        return csv.toString();
    }

    private InterventionStatus loadStatus(String id) {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
            "select status from intervention_state where candidate_id=?")) {
            statement.setString(1, id);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? InterventionStatus.valueOf(row.getString(1)) : InterventionStatus.OPEN;
            }
        } catch (SQLException error) {
            throw new IllegalStateException("无法读取干预状态", error);
        }
    }

    private Connection open() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + databasePath);
    }

    private static CandidateRule classify(ClassAssignment assignment, AssignmentAnalyticsRow row, Instant now) {
        if (row.status() == AssignmentStudentStatus.NOT_SUBMITTED && assignment.dueAt() != null
            && assignment.dueAt().isBefore(now)) {
            return new CandidateRule(DiagnosisReasonCode.OVERDUE_TASK, 100, "任务已逾期且尚无有效提交");
        }
        if (row.status() == AssignmentStudentStatus.FAILED && row.attemptCount() >= 2) {
            return new CandidateRule(DiagnosisReasonCode.REPEATED_FAILURE, 85,
                "已尝试 " + row.attemptCount() + " 次，仍未通过");
        }
        if (row.status() == AssignmentStudentStatus.FAILED || row.status() == AssignmentStudentStatus.SUBMITTED) {
            return new CandidateRule(DiagnosisReasonCode.STALE_PROGRESS, 65,
                "已有提交但尚未通过，建议查看证据并反馈");
        }
        return null;
    }

    private static String displayName(AssignmentAnalyticsRow row) {
        if (row.displayName() != null && !row.displayName().isBlank()) return row.displayName();
        return row.userId();
    }

    private static String stableId(String classroomId, String assignmentId, String userId,
                                   DiagnosisReasonCode reason, int attempts, Instant last) {
        String raw = classroomId + "|" + assignmentId + "|" + userId + "|" + reason + "|" + attempts + "|" + last;
        try {
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash).substring(0, 32);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void row(StringBuilder csv, Object... values) {
        for (int index = 0; index < values.length; index++) {
            if (index > 0) csv.append(',');
            String value = String.valueOf(values[index]);
            if (!value.isEmpty() && "=+-@".indexOf(value.charAt(0)) >= 0) value = "'" + value;
            csv.append('"').append(value.replace("\"", "\"\"")).append('"');
        }
        csv.append("\r\n");
    }

    private record CandidateRule(DiagnosisReasonCode reason, int priority, String summary) { }
}

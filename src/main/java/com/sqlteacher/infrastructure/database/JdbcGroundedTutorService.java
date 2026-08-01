package com.sqlteacher.infrastructure.database;

import com.sqlteacher.application.ai.AiContextPreview;
import com.sqlteacher.application.event.LearningEventOwnerProvider;
import com.sqlteacher.application.knowledge.CourseKnowledgeSearchFilter;
import com.sqlteacher.application.knowledge.GroundedKnowledgeAnswer;
import com.sqlteacher.application.knowledge.GroundedKnowledgeExplanationService;
import com.sqlteacher.application.planning.GroundedTutorResult;
import com.sqlteacher.application.planning.GroundedTutorService;
import com.sqlteacher.application.planning.TutorFeedbackType;
import com.sqlteacher.domain.SqlTeacherException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/** Stores only grounded-session metadata and enum feedback, never full questions or answers. */
public final class JdbcGroundedTutorService implements GroundedTutorService {
    private final GroundedKnowledgeExplanationService explanations;
    private final JdbcConnectionFactory connections;
    private final LearningEventOwnerProvider owners;
    private final Clock clock;

    public JdbcGroundedTutorService(GroundedKnowledgeExplanationService explanations,
                                    JdbcConnectionFactory connections, LearningEventOwnerProvider owners) {
        this(explanations, connections, owners, Clock.systemUTC());
    }

    JdbcGroundedTutorService(GroundedKnowledgeExplanationService explanations, JdbcConnectionFactory connections,
                             LearningEventOwnerProvider owners, Clock clock) {
        this.explanations = Objects.requireNonNull(explanations);
        this.connections = Objects.requireNonNull(connections);
        this.owners = Objects.requireNonNull(owners);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public AiContextPreview preview(String question, CourseKnowledgeSearchFilter filter) {
        return explanations.preview(question, filter);
    }

    @Override
    public GroundedTutorResult ask(String courseScope, String objectiveId, String question,
                                   CourseKnowledgeSearchFilter filter) {
        String course = required(courseScope, "courseScope", 160);
        String objective = required(objectiveId, "objectiveId", 160);
        GroundedKnowledgeAnswer answer = explanations.explain(question, filter);
        String id = UUID.randomUUID().toString();
        String resultCode = answer.citations().isEmpty() ? "NO_EVIDENCE"
            : answer.aiGenerated() ? "GROUNDED" : "DEGRADED";
        String provider = answer.aiGenerated() ? "AI" : "DETERMINISTIC";
        try (var connection = connections.open("app"); PreparedStatement statement = connection.prepareStatement("""
            insert into grounded_tutor_session(id,owner_id,course_id,objective_id,retrieval_snapshot_hash,
                provider,model,result_code,degraded,created_at) values(?,?,?,?,?,?,?,?,?,?)
            """)) {
            statement.setString(1, id); statement.setString(2, owner()); statement.setString(3, course);
            statement.setString(4, objective); statement.setString(5, snapshotHash(answer));
            statement.setString(6, provider); statement.setString(7, answer.model());
            statement.setString(8, resultCode); statement.setInt(9, answer.aiGenerated() ? 0 : 1);
            statement.setString(10, clock.instant().toString()); statement.executeUpdate();
            return new GroundedTutorResult(id, course, objective, answer);
        } catch (SQLException error) {
            throw database("TUTOR_SESSION_SAVE_FAILED", error);
        }
    }

    @Override
    public void feedback(String sessionId, TutorFeedbackType type, String note) {
        String id = required(sessionId, "sessionId", 80);
        if (type == null) throw new IllegalArgumentException("feedback type must not be null");
        String value = note == null ? "" : note.trim();
        if (value.length() > 200) throw new IllegalArgumentException("feedback note must not exceed 200 characters");
        try (var connection = connections.open("app")) {
            try (PreparedStatement ownerCheck = connection.prepareStatement(
                "select 1 from grounded_tutor_session where id=? and owner_id=?")) {
                ownerCheck.setString(1, id); ownerCheck.setString(2, owner());
                try (ResultSet row = ownerCheck.executeQuery()) {
                    if (!row.next()) throw new SecurityException("Tutor session is not owned by the current account");
                }
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                insert into grounded_tutor_feedback(session_id,feedback_type,note,created_at) values(?,?,?,?)
                on conflict(session_id) do update set feedback_type=excluded.feedback_type,note=excluded.note,
                    created_at=excluded.created_at
                """)) {
                statement.setString(1, id); statement.setString(2, type.name()); statement.setString(3, value);
                statement.setString(4, clock.instant().toString()); statement.executeUpdate();
            }
        } catch (SQLException error) {
            throw database("TUTOR_FEEDBACK_SAVE_FAILED", error);
        }
    }

    private String owner() {
        String value = owners.currentOwnerId();
        return value == null || value.isBlank() ? LearningEventOwnerProvider.GUEST_OWNER : value.trim();
    }

    private static String snapshotHash(GroundedKnowledgeAnswer answer) {
        String value = answer.citations().stream().map(item -> item.documentId() + ':' + item.revision() + ':'
            + item.chunkIndex()).sorted().collect(java.util.stream.Collectors.joining("|"));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String required(String value, String name, int max) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        String normalized = value.trim();
        if (normalized.length() > max) throw new IllegalArgumentException(name + " is too long");
        return normalized;
    }

    private static SqlTeacherException database(String code, SQLException error) {
        return new SqlTeacherException(code, "Grounded tutor database operation failed", error);
    }
}

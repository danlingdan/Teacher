package com.sqlteacher.infrastructure.database;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlteacher.application.activity.ActivityEvaluationStatus;
import com.sqlteacher.application.activity.ActivityFeedback;
import com.sqlteacher.application.activity.ActivityReviewItem;
import com.sqlteacher.application.activity.ActivityReviewService;
import com.sqlteacher.application.collaboration.DesktopAccessProfile;
import com.sqlteacher.domain.SqlTeacherException;
import com.sqlteacher.domain.activity.ActivityType;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Local review adapter; every read and write requires a teacher/admin access profile. */
public final class JdbcActivityReviewService implements ActivityReviewService {
    private final JdbcConnectionFactory connectionFactory;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper();

    public JdbcActivityReviewService(JdbcConnectionFactory connectionFactory) {
        this(connectionFactory, Clock.systemUTC());
    }

    JdbcActivityReviewService(JdbcConnectionFactory connectionFactory, Clock clock) {
        this.connectionFactory = Objects.requireNonNull(connectionFactory);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public Optional<ActivityReviewItem> latest(DesktopAccessProfile reviewer, String activityId) {
        authorize(reviewer);
        if (activityId == null || activityId.isBlank()) throw new IllegalArgumentException("activityId must not be blank");
        try (Connection connection = connectionFactory.open("app");
             PreparedStatement statement = connection.prepareStatement("""
                 select e.id,e.owner_id,e.activity_id,a.title,e.activity_type,e.status,e.reason_code,
                     e.evidence_summary_json,e.occurred_at
                 from activity_evaluation_result e
                 join learning_activity_definition a on a.id=e.activity_id
                 where e.activity_id=? and e.activity_type<>'SQL'
                 order by e.occurred_at desc,e.id desc limit 1
                 """)) {
            statement.setString(1, activityId.trim());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return Optional.empty();
                String summary = json.readTree(row.getString(8)).path("summary").asText("评价已完成");
                return Optional.of(new ActivityReviewItem(
                    row.getString(1), row.getString(2), row.getString(3), row.getString(4),
                    ActivityType.valueOf(row.getString(5)), ActivityEvaluationStatus.valueOf(row.getString(6)),
                    row.getString(7), summary, Instant.parse(row.getString(9))
                ));
            }
        } catch (Exception error) {
            throw new SqlTeacherException("ACTIVITY_REVIEW_LOAD_FAILED", "Failed to load activity review evidence", error);
        }
    }

    @Override
    public ActivityFeedback publish(DesktopAccessProfile reviewer, String evaluationId, String comment) {
        authorize(reviewer);
        if (evaluationId == null || evaluationId.isBlank()) throw new IllegalArgumentException("evaluationId must not be blank");
        if (comment == null || comment.isBlank()) throw new IllegalArgumentException("comment must not be blank");
        String id = UUID.randomUUID().toString();
        Instant now = clock.instant();
        try (Connection connection = connectionFactory.open("app")) {
            EvaluationTarget target = target(connection, evaluationId.trim());
            try (PreparedStatement statement = connection.prepareStatement("""
                insert into activity_feedback(id,owner_id,activity_id,evaluation_id,author_id,status,comment,
                    reason_code,created_at,updated_at) values (?,?,?,?,?,'PUBLISHED',?,'TEACHER_FEEDBACK',?,?)
                """)) {
                statement.setString(1, id); statement.setString(2, target.ownerId());
                statement.setString(3, target.activityId()); statement.setString(4, evaluationId.trim());
                statement.setString(5, reviewer.userId()); statement.setString(6, comment.trim());
                statement.setString(7, now.toString()); statement.setString(8, now.toString());
                statement.executeUpdate();
            }
            return new ActivityFeedback(id, target.activityId(), reviewer.userId(), comment, now);
        } catch (SQLException error) {
            throw new SqlTeacherException("ACTIVITY_FEEDBACK_SAVE_FAILED", "Failed to publish activity feedback", error);
        }
    }

    private static EvaluationTarget target(Connection connection, String evaluationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "select owner_id,activity_id from activity_evaluation_result where id=? and activity_type<>'SQL'")) {
            statement.setString(1, evaluationId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new SqlTeacherException("ACTIVITY_EVALUATION_NOT_FOUND", "Evaluation is not available");
                return new EvaluationTarget(row.getString(1), row.getString(2));
            }
        }
    }

    private static void authorize(DesktopAccessProfile reviewer) {
        Objects.requireNonNull(reviewer, "reviewer must not be null");
        if (reviewer.kind() != DesktopAccessProfile.Kind.TEACHER
                && reviewer.kind() != DesktopAccessProfile.Kind.ADMIN) {
            throw new SecurityException("Teacher or administrator permission is required");
        }
    }

    private record EvaluationTarget(String ownerId, String activityId) { }
}

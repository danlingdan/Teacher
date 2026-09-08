package com.sqlteacher.infrastructure.database;

import com.sqlteacher.application.event.LearningEventOwnerProvider;
import com.sqlteacher.application.exercise.ExerciseProgressItem;
import com.sqlteacher.application.exercise.ExerciseProgressOverview;
import com.sqlteacher.application.exercise.ExerciseProgressService;
import com.sqlteacher.domain.SqlTeacherException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class JdbcExerciseProgressService implements ExerciseProgressService {
    private final JdbcConnectionFactory connectionFactory;
    private final LearningEventOwnerProvider ownerProvider;

    public JdbcExerciseProgressService(JdbcConnectionFactory connectionFactory,
                                       LearningEventOwnerProvider ownerProvider) {
        this.connectionFactory = connectionFactory;
        this.ownerProvider = Objects.requireNonNull(ownerProvider);
    }

    @Override
    public ExerciseProgressOverview overview() {
        String sql = """
            with owner_sessions as (
                select id, exercise_id, hints_used from exercise_sessions where owner_id = ?
            )
            select
                (select count(*) from owner_sessions) as sessions,
                (select count(*) from exercise_attempts a
                    join owner_sessions s on s.id = a.session_id) as attempts,
                (select count(*) from exercise_attempts a
                    join owner_sessions s on s.id = a.session_id
                    where a.status in ('PASSED', 'FAILED')) as submissions,
                (select count(*) from exercise_attempts a
                    join owner_sessions s on s.id = a.session_id
                    where a.status = 'PASSED') as passed_submissions,
                coalesce((select avg(a.duration_ms) from exercise_attempts a
                    join owner_sessions s on s.id = a.session_id
                    where a.status in ('PASSED', 'FAILED')), 0) as avg_duration,
                coalesce((select sum(s.hints_used) from owner_sessions s), 0) as hints_used,
                (select count(distinct s.exercise_id) from exercise_attempts a
                    join owner_sessions s on s.id = a.session_id where a.status = 'PASSED') as completed_exercises
            """;
        try (Connection connection = connectionFactory.open("app");
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, currentOwnerId());
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                int submissions = row.getInt("submissions");
                int passed = row.getInt("passed_submissions");
                return new ExerciseProgressOverview(
                    row.getInt("sessions"), row.getInt("attempts"), submissions, passed,
                    submissions == 0 ? 0 : (double) passed / submissions,
                    Duration.ofMillis(Math.max(0, Math.round(row.getDouble("avg_duration")))),
                    row.getInt("hints_used"), row.getInt("completed_exercises")
                );
            }
        } catch (SQLException | IllegalArgumentException error) {
            throw new SqlTeacherException("EXERCISE_PROGRESS_READ_FAILED", "Failed to read exercise progress", error);
        }
    }

    @Override
    public List<ExerciseProgressItem> listExerciseProgress() {
        String sql = """
            select e.id, e.title, e.knowledge_point,
                count(a.id) as attempts,
                coalesce(sum(case when a.status = 'FAILED' then 1 else 0 end), 0) as failed_submissions,
                coalesce(max(case when a.status = 'PASSED' then 1 else 0 end), 0) as passed,
                max(a.created_at) as last_attempt_at
            from exercises e
            left join exercise_sessions s on s.exercise_id = e.id and s.owner_id = ?
            left join exercise_attempts a on a.session_id = s.id
            group by e.id, e.title, e.knowledge_point
            order by passed asc, failed_submissions desc, attempts desc, e.title
            """;
        try (Connection connection = connectionFactory.open("app");
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, currentOwnerId());
            try (ResultSet rows = statement.executeQuery()) {
                List<ExerciseProgressItem> result = new ArrayList<>();
                while (rows.next()) {
                    String lastAttempt = rows.getString("last_attempt_at");
                    result.add(new ExerciseProgressItem(
                        rows.getString("id"), rows.getString("title"), rows.getString("knowledge_point"),
                        rows.getInt("attempts"), rows.getInt("failed_submissions"), rows.getBoolean("passed"),
                        lastAttempt == null ? null : Instant.parse(lastAttempt)
                    ));
                }
                return List.copyOf(result);
            }
        } catch (SQLException | IllegalArgumentException error) {
            throw new SqlTeacherException("EXERCISE_PROGRESS_READ_FAILED", "Failed to read exercise progress", error);
        }
    }

    private String currentOwnerId() {
        String owner = ownerProvider.currentOwnerId();
        return owner == null || owner.isBlank() ? LearningEventOwnerProvider.GUEST_OWNER : owner.trim();
    }
}

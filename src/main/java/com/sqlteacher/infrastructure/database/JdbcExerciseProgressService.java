package com.sqlteacher.infrastructure.database;

import com.sqlteacher.application.exercise.ExerciseProgressItem;
import com.sqlteacher.application.exercise.ExerciseProgressOverview;
import com.sqlteacher.application.exercise.ExerciseProgressService;
import com.sqlteacher.domain.SqlTeacherException;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class JdbcExerciseProgressService implements ExerciseProgressService {
    private final JdbcConnectionFactory connectionFactory;

    public JdbcExerciseProgressService(JdbcConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    @Override
    public ExerciseProgressOverview overview() {
        String sql = """
            select
                (select count(*) from exercise_sessions) as sessions,
                (select count(*) from exercise_attempts) as attempts,
                (select count(*) from exercise_attempts where status in ('PASSED', 'FAILED')) as submissions,
                (select count(*) from exercise_attempts where status = 'PASSED') as passed_submissions,
                coalesce((select avg(duration_ms) from exercise_attempts where status in ('PASSED', 'FAILED')), 0) as avg_duration,
                coalesce((select sum(hints_used) from exercise_sessions), 0) as hints_used,
                (select count(distinct s.exercise_id) from exercise_attempts a
                    join exercise_sessions s on s.id = a.session_id where a.status = 'PASSED') as completed_exercises
            """;
        try (Connection connection = connectionFactory.open("app");
             Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery(sql)) {
            row.next();
            int submissions = row.getInt("submissions");
            int passed = row.getInt("passed_submissions");
            return new ExerciseProgressOverview(
                row.getInt("sessions"), row.getInt("attempts"), submissions, passed,
                submissions == 0 ? 0 : (double) passed / submissions,
                Duration.ofMillis(Math.max(0, Math.round(row.getDouble("avg_duration")))),
                row.getInt("hints_used"), row.getInt("completed_exercises")
            );
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
            left join exercise_sessions s on s.exercise_id = e.id
            left join exercise_attempts a on a.session_id = s.id
            group by e.id, e.title, e.knowledge_point
            order by passed asc, failed_submissions desc, attempts desc, e.title
            """;
        try (Connection connection = connectionFactory.open("app");
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
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
        } catch (SQLException | IllegalArgumentException error) {
            throw new SqlTeacherException("EXERCISE_PROGRESS_READ_FAILED", "Failed to read exercise progress", error);
        }
    }
}

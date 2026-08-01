package com.sqlteacher.infrastructure.database;

import com.sqlteacher.application.maintenance.DataMaintenanceService;
import com.sqlteacher.application.maintenance.LearningDataResetResult;
import com.sqlteacher.domain.SqlTeacherException;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

public final class JdbcDataMaintenanceService implements DataMaintenanceService {
    private final JdbcConnectionFactory connectionFactory;

    public JdbcDataMaintenanceService(JdbcConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    @Override
    public LearningDataResetResult resetLearningData() {
        try (Connection connection = connectionFactory.open("app")) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                int attempts = statement.executeUpdate("delete from exercise_attempts");
                int sessions = statement.executeUpdate("delete from exercise_sessions");
                int events = statement.executeUpdate("delete from learning_events");
                statement.executeUpdate("delete from mastery_snapshot");
                statement.executeUpdate("delete from learning_action_state");
                statement.executeUpdate("delete from study_plan_evidence");
                statement.executeUpdate("delete from study_plan_action");
                statement.executeUpdate("delete from study_plan_snapshot");
                statement.executeUpdate("delete from study_plan_outbox");
                statement.executeUpdate("delete from grounded_tutor_feedback");
                statement.executeUpdate("delete from grounded_tutor_session");
                connection.commit();
                return new LearningDataResetResult(sessions, attempts, events);
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                throw error;
            }
        } catch (SQLException error) {
            throw new SqlTeacherException("LEARNING_DATA_RESET_FAILED", "Failed to reset learning data", error);
        }
    }
}

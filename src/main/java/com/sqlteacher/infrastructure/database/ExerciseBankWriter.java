package com.sqlteacher.infrastructure.database;

import com.sqlteacher.domain.SqlTeacherException;
import com.sqlteacher.domain.exercise.ExerciseDataset;
import com.sqlteacher.domain.exercise.ExerciseDefinition;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;

/**
 * Version-based upsert of exercise bank content into the stored catalog. Shared by the
 * bundled bank (startup) and the network bank sync so both channels obey identical
 * versioning semantics: stored exercises with equal or higher versions win, and datasets
 * are content-addressed (a content change requires a new dataset ID).
 */
final class ExerciseBankWriter {
    private final ExercisePackageCodec codec = new ExercisePackageCodec();

    enum DatasetOutcome {
        INSERTED, IDENTICAL, CONFLICT
    }

    enum ExerciseOutcome {
        INSERTED, UPDATED, SKIPPED
    }

    DatasetOutcome upsertDataset(Connection connection, ExerciseDataset dataset) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "select name, setup_sql from exercise_datasets where id = ?"
        )) {
            statement.setString(1, dataset.id());
            try (ResultSet row = statement.executeQuery()) {
                if (row.next()) {
                    boolean identical = dataset.name().equals(row.getString("name"))
                        && dataset.setupSql().equals(row.getString("setup_sql"));
                    return identical ? DatasetOutcome.IDENTICAL : DatasetOutcome.CONFLICT;
                }
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(
            "insert into exercise_datasets(id, name, setup_sql, version, created_at, updated_at)"
                + " values (?, ?, ?, ?, ?, ?)"
        )) {
            Instant now = Instant.now();
            statement.setString(1, dataset.id());
            statement.setString(2, dataset.name());
            statement.setString(3, dataset.setupSql());
            statement.setInt(4, dataset.version());
            statement.setString(5, now.toString());
            statement.setString(6, now.toString());
            statement.executeUpdate();
            return DatasetOutcome.INSERTED;
        }
    }

    ExerciseOutcome upsertExercise(Connection connection, ExerciseDefinition exercise) throws SQLException {
        Integer storedVersion = null;
        try (PreparedStatement statement = connection.prepareStatement(
            "select version from exercises where id = ?"
        )) {
            statement.setString(1, exercise.id());
            try (ResultSet row = statement.executeQuery()) {
                if (row.next()) {
                    storedVersion = row.getInt("version");
                }
            }
        }
        if (storedVersion != null && storedVersion >= exercise.version()) {
            return ExerciseOutcome.SKIPPED;
        }
        if (storedVersion == null) {
            try (PreparedStatement statement = connection.prepareStatement(
                "insert into exercises("
                    + "id, title, description, knowledge_point, difficulty, dataset_id, reference_sql, "
                    + "evaluation_rule_json, hints_json, version, enabled, created_at, updated_at, "
                    + "exercise_type, type_config_json"
                    + ") values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
            )) {
                bindExercise(statement, exercise, exercise.createdAt(), Instant.now());
                statement.executeUpdate();
                return ExerciseOutcome.INSERTED;
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(
            "update exercises set title = ?, description = ?, knowledge_point = ?, difficulty = ?, "
                + "dataset_id = ?, reference_sql = ?, evaluation_rule_json = ?, hints_json = ?, "
                + "version = ?, enabled = ?, updated_at = ?, exercise_type = ?, type_config_json = ? "
                + "where id = ? and version = ?"
        )) {
            Instant now = Instant.now();
            statement.setString(1, exercise.title());
            statement.setString(2, exercise.description());
            statement.setString(3, exercise.knowledgePoint());
            statement.setString(4, exercise.difficulty().name());
            statement.setString(5, exercise.datasetId());
            statement.setString(6, exercise.referenceSql());
            statement.setString(7, codec.encodeRule(exercise.evaluationRule()));
            statement.setString(8, codec.encodeHints(exercise.hints()));
            statement.setInt(9, exercise.version());
            statement.setBoolean(10, exercise.enabled());
            statement.setString(11, now.toString());
            bindTypeConfig(statement, exercise, 12, 13);
            statement.setString(14, exercise.id());
            statement.setInt(15, storedVersion);
            if (statement.executeUpdate() != 1) {
                throw new SqlTeacherException(
                    "EXERCISE_BANK_INVALID", "Exercise " + exercise.id() + " changed during bank application"
                );
            }
            return ExerciseOutcome.UPDATED;
        }
    }

    private void bindTypeConfig(
        PreparedStatement statement, ExerciseDefinition exercise, int typeIndex, int configIndex
    ) throws SQLException {
        statement.setString(typeIndex, exercise.exerciseType().name());
        statement.setString(configIndex, codec.encodeTypeConfig(
            exercise.verificationSql(), exercise.allowedStatementTypes(),
            exercise.expectedAffectedRows(), exercise.requiredTransactionKeywords(),
            exercise.triggerProbeSql()
        ));
    }

    private void bindExercise(
        PreparedStatement statement, ExerciseDefinition exercise, Instant createdAt, Instant updatedAt
    ) throws SQLException {
        statement.setString(1, exercise.id());
        statement.setString(2, exercise.title());
        statement.setString(3, exercise.description());
        statement.setString(4, exercise.knowledgePoint());
        statement.setString(5, exercise.difficulty().name());
        statement.setString(6, exercise.datasetId());
        statement.setString(7, exercise.referenceSql());
        statement.setString(8, codec.encodeRule(exercise.evaluationRule()));
        statement.setString(9, codec.encodeHints(exercise.hints()));
        statement.setInt(10, exercise.version());
        statement.setBoolean(11, exercise.enabled());
        statement.setString(12, createdAt.toString());
        statement.setString(13, updatedAt.toString());
        bindTypeConfig(statement, exercise, 14, 15);
    }
}

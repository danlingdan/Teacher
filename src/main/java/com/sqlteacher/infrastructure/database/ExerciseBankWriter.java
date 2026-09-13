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
                ExerciseStatements.INSERT_EXERCISE_SQL
            )) {
                ExerciseStatements.bindInsert(statement, exercise, exercise.createdAt(), Instant.now());
                statement.executeUpdate();
                return ExerciseOutcome.INSERTED;
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(
            ExerciseStatements.UPDATE_EXERCISE_SQL
        )) {
            Instant now = Instant.now();
            ExerciseStatements.bindOptimisticUpdate(statement, exercise, now, storedVersion);
            if (statement.executeUpdate() != 1) {
                throw new SqlTeacherException(
                    "EXERCISE_BANK_INVALID", "Exercise " + exercise.id() + " changed during bank application"
                );
            }
            return ExerciseOutcome.UPDATED;
        }
    }
}

package com.sqlteacher.infrastructure.database;

import com.sqlteacher.domain.SqlTeacherException;
import com.sqlteacher.domain.exercise.ExerciseChapterPath;
import com.sqlteacher.domain.exercise.ExerciseDataset;
import com.sqlteacher.domain.exercise.ExerciseDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Version-based upsert of exercise bank content into the stored catalog. Shared by the
 * bundled bank (startup) and the network bank sync so both channels obey identical
 * versioning semantics: stored exercises with equal or higher versions win, and datasets
 * are content-addressed (a content change requires a new dataset ID).
 */
final class ExerciseBankWriter {
    /** Shared mapper for chapter JSON encoding; constructing one per call is expensive. */
    private static final ObjectMapper JSON = new ObjectMapper();

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

    /** v3.5.0 EPATH-1：章节路径按版本覆盖写入（高版本胜出），章节结构整体序列化为 JSON。 */
    PathOutcome upsertPath(Connection connection, ExerciseChapterPath path) throws SQLException {
        Integer storedVersion = null;
        try (PreparedStatement statement = connection.prepareStatement(
            "select version from exercise_paths where id = ?"
        )) {
            statement.setString(1, path.id());
            try (ResultSet row = statement.executeQuery()) {
                if (row.next()) {
                    storedVersion = row.getInt("version");
                }
            }
        }
        if (storedVersion != null && storedVersion >= path.version()) {
            return PathOutcome.SKIPPED;
        }
        try (PreparedStatement statement = connection.prepareStatement(
            "insert into exercise_paths(id, name, version, chapters_json, updated_at)"
                + " values (?, ?, ?, ?, ?)"
                + " on conflict(id) do update set name = excluded.name,"
                + " version = excluded.version, chapters_json = excluded.chapters_json,"
                + " updated_at = excluded.updated_at"
        )) {
            statement.setString(1, path.id());
            statement.setString(2, path.name());
            statement.setInt(3, path.version());
            statement.setString(4, encodeChaptersJson(path.chapters()));
            statement.setString(5, Instant.now().toString());
            statement.executeUpdate();
            return storedVersion == null ? PathOutcome.INSERTED : PathOutcome.UPDATED;
        }
    }

    enum PathOutcome {
        INSERTED, UPDATED, SKIPPED
    }

    private static String encodeChaptersJson(List<ExerciseChapterPath.Chapter> chapters) {
        List<Map<String, Object>> payload = new ArrayList<>();
        for (ExerciseChapterPath.Chapter chapter : chapters) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("order", chapter.order());
            item.put("title", chapter.title());
            item.put("knowledgeTags", chapter.knowledgeTags());
            item.put("exerciseIds", chapter.exerciseIds());
            payload.add(item);
        }
        try {
            return JSON.writeValueAsString(payload);
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new IllegalStateException("Chapter JSON encoding failed", error);
        }
    }
}

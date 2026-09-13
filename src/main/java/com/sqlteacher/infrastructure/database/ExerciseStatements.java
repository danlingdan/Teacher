package com.sqlteacher.infrastructure.database;

import com.sqlteacher.domain.exercise.ExerciseDefinition;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;

/**
 * Single source of the exercise-row insert and optimistic-lock update SQL plus their fixed
 * parameter binding, shared by the bank writer and the management service. The update always
 * matches {@code where id = ? and version = ?}; bind positions and bind types are part of the
 * contract and must not be reordered independently by either caller.
 */
final class ExerciseStatements {

    static final String INSERT_EXERCISE_SQL = """
        insert into exercises(
            id, title, description, knowledge_point, difficulty, dataset_id, reference_sql,
            evaluation_rule_json, hints_json, version, enabled, created_at, updated_at,
            exercise_type, type_config_json
        ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;

    static final String UPDATE_EXERCISE_SQL = """
        update exercises set title = ?, description = ?, knowledge_point = ?, difficulty = ?,
            dataset_id = ?, reference_sql = ?, evaluation_rule_json = ?, hints_json = ?,
            version = ?, enabled = ?, updated_at = ?, exercise_type = ?, type_config_json = ?
        where id = ? and version = ?
        """;

    private static final ExercisePackageCodec CODEC = new ExercisePackageCodec();

    private ExerciseStatements() { }

    /** Binds the 15 insert parameters: id first, timestamps at 12-13, type config at 14-15. */
    static void bindInsert(
        PreparedStatement statement, ExerciseDefinition exercise, Instant createdAt, Instant updatedAt
    ) throws SQLException {
        statement.setString(1, exercise.id());
        statement.setString(2, exercise.title());
        statement.setString(3, exercise.description());
        statement.setString(4, exercise.knowledgePoint());
        statement.setString(5, exercise.difficulty().name());
        statement.setString(6, exercise.datasetId());
        statement.setString(7, exercise.referenceSql());
        statement.setString(8, CODEC.encodeRule(exercise.evaluationRule()));
        statement.setString(9, CODEC.encodeHints(exercise.hints()));
        statement.setInt(10, exercise.version());
        statement.setBoolean(11, exercise.enabled());
        statement.setString(12, createdAt.toString());
        statement.setString(13, updatedAt.toString());
        statement.setString(14, exercise.exerciseType().name());
        statement.setString(15, CODEC.encodeTypeConfig(
            exercise.verificationSql(), exercise.allowedStatementTypes(),
            exercise.expectedAffectedRows(), exercise.requiredTransactionKeywords(),
            exercise.triggerProbeSql(), exercise.expectedColumns(),
            exercise.revealMode().name()
        ));
    }

    /**
     * Binds the 15 optimistic-lock update parameters: columns at 1-13 (updated_at at 11),
     * then id and expected stored version at 14-15.
     */
    static void bindOptimisticUpdate(
        PreparedStatement statement, ExerciseDefinition exercise, Instant updatedAt, int expectedVersion
    ) throws SQLException {
        statement.setString(1, exercise.title());
        statement.setString(2, exercise.description());
        statement.setString(3, exercise.knowledgePoint());
        statement.setString(4, exercise.difficulty().name());
        statement.setString(5, exercise.datasetId());
        statement.setString(6, exercise.referenceSql());
        statement.setString(7, CODEC.encodeRule(exercise.evaluationRule()));
        statement.setString(8, CODEC.encodeHints(exercise.hints()));
        statement.setInt(9, exercise.version());
        statement.setBoolean(10, exercise.enabled());
        statement.setString(11, updatedAt.toString());
        statement.setString(12, exercise.exerciseType().name());
        statement.setString(13, CODEC.encodeTypeConfig(
            exercise.verificationSql(), exercise.allowedStatementTypes(),
            exercise.expectedAffectedRows(), exercise.requiredTransactionKeywords(),
            exercise.triggerProbeSql(), exercise.expectedColumns(),
            exercise.revealMode().name()
        ));
        statement.setString(14, exercise.id());
        statement.setInt(15, expectedVersion);
    }
}

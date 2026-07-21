package com.sqlteacher.infrastructure.database;

import com.sqlteacher.application.exercise.ExerciseDraft;
import com.sqlteacher.application.exercise.ExerciseImportResult;
import com.sqlteacher.application.exercise.ExerciseManagementService;
import com.sqlteacher.application.exercise.ExerciseSummary;
import com.sqlteacher.domain.SqlTeacherException;
import com.sqlteacher.domain.exercise.ExerciseDataset;
import com.sqlteacher.domain.exercise.ExerciseDefinition;
import com.sqlteacher.domain.exercise.ExerciseDifficulty;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class JdbcExerciseManagementService implements ExerciseManagementService {
    private final JdbcConnectionFactory connectionFactory;
    private final ExercisePackageCodec codec;

    public JdbcExerciseManagementService(JdbcConnectionFactory connectionFactory) {
        this(connectionFactory, new ExercisePackageCodec());
    }

    JdbcExerciseManagementService(JdbcConnectionFactory connectionFactory, ExercisePackageCodec codec) {
        this.connectionFactory = connectionFactory;
        this.codec = codec;
    }

    @Override
    public List<ExerciseSummary> listExercises(boolean includeDisabled) {
        String sql = """
            select id, title, knowledge_point, difficulty, version, enabled
            from exercises
            where enabled = 1 or ? = 1
            order by difficulty, knowledge_point, title, id
            """;
        try (Connection connection = connectionFactory.open("app");
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBoolean(1, includeDisabled);
            try (ResultSet rows = statement.executeQuery()) {
                List<ExerciseSummary> result = new ArrayList<>();
                while (rows.next()) {
                    result.add(new ExerciseSummary(
                        rows.getString("id"), rows.getString("title"), rows.getString("knowledge_point"),
                        ExerciseDifficulty.valueOf(rows.getString("difficulty")), rows.getInt("version"),
                        rows.getBoolean("enabled")
                    ));
                }
                return List.copyOf(result);
            }
        } catch (SQLException | IllegalArgumentException error) {
            throw failure("EXERCISE_LIST_FAILED", "Failed to list exercises", error);
        }
    }

    @Override
    public Optional<ExerciseDefinition> findDefinition(String exerciseId) {
        try (Connection connection = connectionFactory.open("app")) {
            return findDefinition(connection, requireId(exerciseId));
        } catch (SQLException | IllegalArgumentException error) {
            throw failure("EXERCISE_READ_FAILED", "Failed to read exercise", error);
        }
    }

    @Override
    public List<ExerciseDataset> listDatasets() {
        String sql = "select id, name, setup_sql, version from exercise_datasets order by name, id";
        try (Connection connection = connectionFactory.open("app");
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            List<ExerciseDataset> result = new ArrayList<>();
            while (rows.next()) {
                result.add(readDataset(rows));
            }
            return List.copyOf(result);
        } catch (SQLException | IllegalArgumentException error) {
            throw failure("EXERCISE_DATASET_LIST_FAILED", "Failed to list exercise datasets", error);
        }
    }

    @Override
    public ExerciseDefinition save(ExerciseDraft draft) {
        if (draft == null) {
            throw new IllegalArgumentException("draft must not be null");
        }
        try (Connection connection = connectionFactory.open("app")) {
            requireDataset(connection, draft.datasetId());
            Optional<ExerciseDefinition> existing = draft.id().isEmpty()
                ? Optional.empty()
                : findDefinition(connection, draft.id());
            if (existing.isEmpty()) {
                if (draft.expectedVersion() != null) {
                    throw conflict("Cannot update a missing exercise");
                }
                Instant now = Instant.now();
                ExerciseDefinition created = fromDraft(draft, draft.id().isEmpty() ? UUID.randomUUID().toString() : draft.id(), 1, now, now);
                insertExercise(connection, created);
                return created;
            }
            ExerciseDefinition current = existing.get();
            if (draft.expectedVersion() == null || draft.expectedVersion() != current.version()) {
                throw conflict("Exercise was changed by another operation");
            }
            ExerciseDefinition updated = fromDraft(
                draft, current.id(), current.version() + 1, current.createdAt(), Instant.now()
            );
            updateExercise(connection, updated, current.version());
            return updated;
        } catch (SqlTeacherException error) {
            throw error;
        } catch (SQLException | IllegalArgumentException error) {
            throw failure("EXERCISE_SAVE_FAILED", "Failed to save exercise", error);
        }
    }

    @Override
    public ExerciseDefinition copy(String exerciseId, String newTitle) {
        String title = requireText(newTitle, "newTitle");
        try (Connection connection = connectionFactory.open("app")) {
            ExerciseDefinition source = findDefinition(connection, requireId(exerciseId))
                .orElseThrow(() -> notFound(exerciseId));
            Instant now = Instant.now();
            ExerciseDefinition copy = new ExerciseDefinition(
                UUID.randomUUID().toString(), title, source.description(), source.knowledgePoint(),
                source.difficulty(), source.datasetId(), source.referenceSql(), source.evaluationRule(),
                source.hints(), 1, false, now, now
            );
            insertExercise(connection, copy);
            return copy;
        } catch (SqlTeacherException error) {
            throw error;
        } catch (SQLException | IllegalArgumentException error) {
            throw failure("EXERCISE_COPY_FAILED", "Failed to copy exercise", error);
        }
    }

    @Override
    public ExerciseDefinition setEnabled(String exerciseId, boolean enabled, int expectedVersion) {
        try (Connection connection = connectionFactory.open("app")) {
            ExerciseDefinition current = findDefinition(connection, requireId(exerciseId))
                .orElseThrow(() -> notFound(exerciseId));
            if (expectedVersion != current.version()) {
                throw conflict("Exercise was changed by another operation");
            }
            String sql = "update exercises set enabled = ?, version = version + 1, updated_at = ? where id = ? and version = ?";
            Instant updatedAt = Instant.now();
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setBoolean(1, enabled);
                statement.setString(2, updatedAt.toString());
                statement.setString(3, current.id());
                statement.setInt(4, expectedVersion);
                if (statement.executeUpdate() != 1) {
                    throw conflict("Exercise was changed by another operation");
                }
            }
            return new ExerciseDefinition(
                current.id(), current.title(), current.description(), current.knowledgePoint(),
                current.difficulty(), current.datasetId(), current.referenceSql(), current.evaluationRule(),
                current.hints(), current.version() + 1, enabled, current.createdAt(), updatedAt
            );
        } catch (SqlTeacherException error) {
            throw error;
        } catch (SQLException | IllegalArgumentException error) {
            throw failure("EXERCISE_ENABLE_FAILED", "Failed to change exercise availability", error);
        }
    }

    @Override
    public String exportPackage(List<String> exerciseIds) {
        if (exerciseIds == null) {
            throw new IllegalArgumentException("exerciseIds must not be null");
        }
        Set<String> requested = new HashSet<>(exerciseIds.stream().map(JdbcExerciseManagementService::requireId).toList());
        try (Connection connection = connectionFactory.open("app")) {
            List<ExerciseDefinition> exercises = readAllDefinitions(connection).stream()
                .filter(exercise -> requested.isEmpty() || requested.contains(exercise.id()))
                .toList();
            if (!requested.isEmpty() && exercises.size() != requested.size()) {
                throw notFound("one or more requested exercise IDs");
            }
            Set<String> datasetIds = exercises.stream().map(ExerciseDefinition::datasetId).collect(java.util.stream.Collectors.toSet());
            List<ExerciseDataset> datasets = readAllDatasets(connection).stream()
                .filter(dataset -> datasetIds.contains(dataset.id()))
                .toList();
            return codec.encode(datasets, exercises);
        } catch (SqlTeacherException error) {
            throw error;
        } catch (SQLException | IllegalArgumentException error) {
            throw failure("EXERCISE_EXPORT_FAILED", "Failed to export exercise package", error);
        }
    }

    @Override
    public ExerciseImportResult importPackage(String packageJson) {
        ExercisePackageCodec.DecodedPackage imported = codec.decode(packageJson);
        Set<String> datasetIds = imported.datasets().stream().map(ExerciseDataset::id).collect(java.util.stream.Collectors.toSet());
        try (Connection connection = connectionFactory.open("app")) {
            connection.setAutoCommit(false);
            try {
                int datasetsInserted = 0;
                for (ExerciseDataset dataset : imported.datasets()) {
                    Optional<ExerciseDataset> existingDataset = findDataset(connection, dataset.id());
                    if (existingDataset.isPresent()) {
                        if (!existingDataset.get().equals(dataset)) {
                            throw conflict("Dataset already exists with different content: " + dataset.id());
                        }
                    } else {
                        insertDataset(connection, dataset);
                        datasetsInserted++;
                    }
                }
                for (ExerciseDefinition exercise : imported.exercises()) {
                    if (findDefinition(connection, exercise.id()).isPresent()) {
                        throw conflict("Exercise already exists: " + exercise.id());
                    }
                    if (!datasetIds.contains(exercise.datasetId()) && !datasetExists(connection, exercise.datasetId())) {
                        throw new SqlTeacherException(
                            "EXERCISE_IMPORT_INVALID", "Missing dataset: " + exercise.datasetId()
                        );
                    }
                    insertExercise(connection, exercise);
                }
                connection.commit();
                List<String> ids = imported.exercises().stream().map(ExerciseDefinition::id).toList();
                return new ExerciseImportResult(datasetsInserted, ids.size(), ids);
            } catch (SQLException | RuntimeException error) {
                rollback(connection, error);
                throw error;
            }
        } catch (SqlTeacherException error) {
            throw error;
        } catch (SQLException | IllegalArgumentException error) {
            throw failure("EXERCISE_IMPORT_FAILED", "Failed to import exercise package", error);
        }
    }

    private Optional<ExerciseDefinition> findDefinition(Connection connection, String id) throws SQLException {
        String sql = "select * from exercises where id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, id);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? Optional.of(readExercise(row)) : Optional.empty();
            }
        }
    }

    private List<ExerciseDefinition> readAllDefinitions(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("select * from exercises order by id")) {
            List<ExerciseDefinition> result = new ArrayList<>();
            while (rows.next()) {
                result.add(readExercise(rows));
            }
            return result;
        }
    }

    private List<ExerciseDataset> readAllDatasets(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("select id, name, setup_sql, version from exercise_datasets order by id")) {
            List<ExerciseDataset> result = new ArrayList<>();
            while (rows.next()) {
                result.add(readDataset(rows));
            }
            return result;
        }
    }

    private ExerciseDefinition readExercise(ResultSet row) throws SQLException {
        return new ExerciseDefinition(
            row.getString("id"), row.getString("title"), row.getString("description"),
            row.getString("knowledge_point"), ExerciseDifficulty.valueOf(row.getString("difficulty")),
            row.getString("dataset_id"), row.getString("reference_sql"),
            codec.decodeRule(row.getString("evaluation_rule_json")), codec.decodeHints(row.getString("hints_json")),
            row.getInt("version"), row.getBoolean("enabled"), Instant.parse(row.getString("created_at")),
            Instant.parse(row.getString("updated_at"))
        );
    }

    private static ExerciseDataset readDataset(ResultSet row) throws SQLException {
        return new ExerciseDataset(
            row.getString("id"), row.getString("name"), row.getString("setup_sql"), row.getInt("version")
        );
    }

    private ExerciseDefinition fromDraft(
        ExerciseDraft draft, String id, int version, Instant createdAt, Instant updatedAt
    ) {
        return new ExerciseDefinition(
            id, draft.title(), draft.description(), draft.knowledgePoint(), draft.difficulty(),
            draft.datasetId(), draft.referenceSql(), draft.evaluationRule(), draft.hints(), version,
            draft.enabled(), createdAt, updatedAt
        );
    }

    private void insertExercise(Connection connection, ExerciseDefinition exercise) throws SQLException {
        String sql = """
            insert into exercises(
                id, title, description, knowledge_point, difficulty, dataset_id, reference_sql,
                evaluation_rule_json, hints_json, version, enabled, created_at, updated_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindExercise(statement, exercise);
            statement.executeUpdate();
        }
    }

    private void updateExercise(Connection connection, ExerciseDefinition exercise, int expectedVersion) throws SQLException {
        String sql = """
            update exercises set title = ?, description = ?, knowledge_point = ?, difficulty = ?,
                dataset_id = ?, reference_sql = ?, evaluation_rule_json = ?, hints_json = ?,
                version = ?, enabled = ?, updated_at = ?
            where id = ? and version = ?
            """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
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
            statement.setString(11, exercise.updatedAt().toString());
            statement.setString(12, exercise.id());
            statement.setInt(13, expectedVersion);
            if (statement.executeUpdate() != 1) {
                throw conflict("Exercise was changed by another operation");
            }
        }
    }

    private void bindExercise(PreparedStatement statement, ExerciseDefinition exercise) throws SQLException {
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
        statement.setString(12, exercise.createdAt().toString());
        statement.setString(13, exercise.updatedAt().toString());
    }

    private static void insertDataset(Connection connection, ExerciseDataset dataset) throws SQLException {
        String sql = "insert into exercise_datasets(id, name, setup_sql, version) values (?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, dataset.id());
            statement.setString(2, dataset.name());
            statement.setString(3, dataset.setupSql());
            statement.setInt(4, dataset.version());
            statement.executeUpdate();
        }
    }

    private static boolean datasetExists(Connection connection, String id) throws SQLException {
        return findDataset(connection, id).isPresent();
    }

    private static Optional<ExerciseDataset> findDataset(Connection connection, String id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "select id, name, setup_sql, version from exercise_datasets where id = ?"
        )) {
            statement.setString(1, id);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? Optional.of(readDataset(row)) : Optional.empty();
            }
        }
    }

    private static void requireDataset(Connection connection, String id) throws SQLException {
        if (!datasetExists(connection, id)) {
            throw new SqlTeacherException("EXERCISE_DATASET_NOT_FOUND", "Exercise dataset not found: " + id);
        }
    }

    private static void rollback(Connection connection, Throwable original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackError) {
            original.addSuppressed(rollbackError);
        }
    }

    private static String requireId(String value) {
        return requireText(value, "exerciseId");
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    private static SqlTeacherException notFound(String id) {
        return new SqlTeacherException("EXERCISE_NOT_FOUND", "Exercise not found: " + id);
    }

    private static SqlTeacherException conflict(String message) {
        return new SqlTeacherException("EXERCISE_VERSION_CONFLICT", message);
    }

    private static SqlTeacherException failure(String code, String message, Throwable error) {
        return new SqlTeacherException(code, message, error);
    }
}

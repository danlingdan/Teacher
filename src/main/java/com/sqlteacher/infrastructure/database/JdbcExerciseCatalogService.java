package com.sqlteacher.infrastructure.database;

import com.sqlteacher.application.event.LearningEventOwnerProvider;
import com.sqlteacher.application.exercise.ExerciseCatalogItem;
import com.sqlteacher.application.exercise.ExerciseCatalogService;
import com.sqlteacher.application.exercise.ExerciseManagementService;
import com.sqlteacher.application.exercise.ExerciseSummary;
import com.sqlteacher.application.exercise.ExerciseView;
import com.sqlteacher.domain.SqlTeacherException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class JdbcExerciseCatalogService implements ExerciseCatalogService {
    private final JdbcConnectionFactory connectionFactory;
    private final ExerciseManagementService managementService;
    private final LearningEventOwnerProvider ownerProvider;

    public JdbcExerciseCatalogService(
        JdbcConnectionFactory connectionFactory,
        ExerciseManagementService managementService,
        LearningEventOwnerProvider ownerProvider
    ) {
        this.connectionFactory = connectionFactory;
        this.managementService = managementService;
        this.ownerProvider = ownerProvider;
    }

    @Override
    public List<ExerciseCatalogItem> listAvailableExercises() {
        Map<String, OwnerStatus> statusByExercise = readOwnerStatus();
        return managementService.listExercises(false).stream()
            .map(summary -> toItem(summary, statusByExercise.get(summary.id())))
            .toList();
    }

    @Override
    public Optional<ExerciseView> findAvailableExercise(String exerciseId) {
        return managementService.findDefinition(exerciseId)
            .filter(definition -> definition.enabled())
            .map(definition -> new ExerciseView(
                definition.id(), definition.title(), definition.description(), definition.knowledgePoint(),
                definition.difficulty(), definition.exerciseType(), managementService.listDatasets().stream()
                    .filter(dataset -> dataset.id().equals(definition.datasetId()))
                    .findFirst()
                    .map(dataset -> ExerciseDatasetSchemaSummary.fromSetupSql(dataset.setupSql()))
                    .orElse("暂无数据集字段说明"),
                definition.version()
            ));
    }

    private ExerciseCatalogItem toItem(ExerciseSummary summary, OwnerStatus status) {
        return new ExerciseCatalogItem(
            summary.id(), summary.title(), summary.knowledgePoint(), summary.difficulty(),
            summary.exerciseType(), summary.version(),
            status == null ? 0 : status.attempts(),
            status != null && status.passed(),
            status == null ? null : status.lastAttemptAt()
        );
    }

    private Map<String, OwnerStatus> readOwnerStatus() {
        String owner = ownerProvider.currentOwnerId();
        String normalizedOwner = owner == null || owner.isBlank() ? LearningEventOwnerProvider.GUEST_OWNER : owner.trim();
        String sql = """
            select s.exercise_id,
                   count(a.id) as attempts,
                   max(case when a.status = 'PASSED' then 1 else 0 end) as passed,
                   max(a.created_at) as last_attempt_at
            from exercise_sessions s
            left join exercise_attempts a on a.session_id = s.id
            where s.owner_id = ?
            group by s.exercise_id
            """;
        try (Connection connection = connectionFactory.open("app");
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, normalizedOwner);
            try (ResultSet rows = statement.executeQuery()) {
                Map<String, OwnerStatus> result = new HashMap<>();
                while (rows.next()) {
                    result.put(rows.getString("exercise_id"), new OwnerStatus(
                        rows.getInt("attempts"), rows.getInt("passed") == 1, rows.getString("last_attempt_at")
                    ));
                }
                return result;
            }
        } catch (SQLException | IllegalArgumentException error) {
            throw new SqlTeacherException(
                "EXERCISE_CATALOG_READ_FAILED", "Failed to read the practice catalog", error
            );
        }
    }

    private record OwnerStatus(int attempts, boolean passed, String lastAttemptAt) {
    }
}

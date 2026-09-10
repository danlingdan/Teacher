package com.sqlteacher.infrastructure.database;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlteacher.application.event.LearningEventOwnerProvider;
import com.sqlteacher.application.exercise.ExerciseCatalogItem;
import com.sqlteacher.application.exercise.ExerciseCatalogService;
import com.sqlteacher.application.exercise.ExerciseManagementService;
import com.sqlteacher.application.exercise.ExerciseSummary;
import com.sqlteacher.application.exercise.ExerciseView;
import com.sqlteacher.application.exercise.RecommendationView;
import com.sqlteacher.application.exercise.WrongBookItem;
import com.sqlteacher.domain.SqlTeacherException;
import com.sqlteacher.domain.exercise.ExerciseDifficulty;
import com.sqlteacher.domain.exercise.ExerciseType;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

public final class JdbcExerciseCatalogService implements ExerciseCatalogService {
    private static final ObjectMapper FEEDBACK_MAPPER = new ObjectMapper();

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
                definition.difficulty(), definition.exerciseType(), definition.expectedColumns(),
                managementService.listDatasets().stream()
                    .filter(dataset -> dataset.id().equals(definition.datasetId()))
                    .findFirst()
                    .map(dataset -> ExerciseDatasetSchemaSummary.fromSetupSql(dataset.setupSql()))
                    .orElse("暂无数据集字段说明"),
                definition.version()
            ));
    }

    @Override
    public List<WrongBookItem> wrongBook() {
        String owner = currentOwner();
        // Attempted (has a session) but never passed (no PASSED attempt) — deterministic
        // local aggregation only; the last failed feedback is display text.
        String sql = """
            select e.id, e.title, e.knowledge_point, e.difficulty, e.exercise_type,
                   count(a.id) as attempts,
                   max(a.score) as best_score,
                   max(a.created_at) as last_attempt_at,
                   (select a2.feedback_json from exercise_attempts a2
                      join exercise_sessions s2 on s2.id = a2.session_id
                     where s2.exercise_id = e.id and s2.owner_id = ? and a2.status = 'FAILED'
                     order by a2.created_at desc limit 1) as last_feedback_json
            from exercises e
            join exercise_sessions s on s.exercise_id = e.id and s.owner_id = ?
            left join exercise_attempts a on a.session_id = s.id
            where e.enabled = 1
              and not exists (
                  select 1 from exercise_attempts ap
                    join exercise_sessions sp on sp.id = ap.session_id
                   where sp.exercise_id = e.id and sp.owner_id = ? and ap.status = 'PASSED')
            group by e.id, e.title, e.knowledge_point, e.difficulty, e.exercise_type
            order by last_attempt_at desc, e.title
            """;
        try (Connection connection = connectionFactory.open("app");
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, owner);
            statement.setString(2, owner);
            statement.setString(3, owner);
            try (ResultSet rows = statement.executeQuery()) {
                List<WrongBookItem> result = new ArrayList<>();
                while (rows.next()) {
                    Integer bestScore = rows.getObject("best_score") == null ? null : rows.getInt("best_score");
                    result.add(new WrongBookItem(
                        rows.getString("id"), rows.getString("title"), rows.getString("knowledge_point"),
                        ExerciseDifficulty.valueOf(rows.getString("difficulty")),
                        exerciseType(rows.getString("exercise_type")),
                        rows.getInt("attempts"), bestScore,
                        rows.getString("last_attempt_at"),
                        firstFeedback(rows.getString("last_feedback_json"))
                    ));
                }
                return List.copyOf(result);
            }
        } catch (SQLException | IllegalArgumentException error) {
            throw new SqlTeacherException(
                "EXERCISE_WRONG_BOOK_READ_FAILED", "Failed to read the wrong-answer book", error
            );
        }
    }

    @Override
    public Optional<RecommendationView> recommendNextExercise() {
        List<ExerciseCatalogItem> items = listAvailableExercises();
        List<ExerciseCatalogItem> unpassed = items.stream()
            .filter(item -> !item.passed())
            .sorted(Comparator.comparing((ExerciseCatalogItem item) ->
                    difficultyRank(item.difficulty())).thenComparing(ExerciseCatalogItem::title))
            .toList();
        if (unpassed.isEmpty()) {
            return Optional.empty();
        }

        // Knowledge-point accuracy from the same local status map the catalog uses.
        Map<String, List<ExerciseCatalogItem>> byKnowledgePoint = new TreeMap<>();
        for (ExerciseCatalogItem item : items) {
            byKnowledgePoint.computeIfAbsent(item.knowledgePoint(), key -> new ArrayList<>()).add(item);
        }
        String targetPoint = null;
        int accuracy = -1;
        for (Map.Entry<String, List<ExerciseCatalogItem>> entry : byKnowledgePoint.entrySet()) {
            List<ExerciseCatalogItem> attempted = entry.getValue().stream()
                .filter(item -> item.attempts() > 0)
                .toList();
            if (attempted.isEmpty()) {
                continue;
            }
            long passed = attempted.stream().filter(ExerciseCatalogItem::passed).count();
            int pointAccuracy = (int) Math.floor(100.0 * passed / attempted.size());
            if (targetPoint == null || pointAccuracy < accuracy) {
                targetPoint = entry.getKey();
                accuracy = pointAccuracy;
            }
        }

        if (targetPoint == null) {
            ExerciseCatalogItem first = unpassed.getFirst();
            return Optional.of(new RecommendationView(
                first.id(), first.title(), first.knowledgePoint(), first.difficulty(), first.exerciseType(),
                "还没有作答记录，建议从「" + first.knowledgePoint() + "」的入门题开始。"
            ));
        }

        final String selectedPoint = targetPoint;
        List<ExerciseCatalogItem> pointUnpassed = unpassed.stream()
            .filter(item -> item.knowledgePoint().equals(selectedPoint))
            .toList();
        if (pointUnpassed.isEmpty()) {
            pointUnpassed = unpassed;
        }
        final String reasonPoint = selectedPoint;
        final int pointAccuracy = accuracy;
        int targetRank;
        String reason;
        if (accuracy >= 80) {
            targetRank = difficultyRank(ExerciseDifficulty.ADVANCED);
            reason = "「" + reasonPoint + "」正确率 " + pointAccuracy + "%，建议挑战更高难度。";
        } else if (accuracy >= 40) {
            targetRank = difficultyRank(pointUnpassed.getFirst().difficulty());
            reason = "「" + reasonPoint + "」正确率 " + pointAccuracy + "%，建议同难度再练一道。";
        } else {
            targetRank = difficultyRank(ExerciseDifficulty.BEGINNER);
            reason = "「" + reasonPoint + "」正确率 " + pointAccuracy + "%，建议先巩固基础题。";
        }
        ExerciseCatalogItem best = pointUnpassed.stream()
            .min(Comparator
                .comparingInt((ExerciseCatalogItem item) ->
                    Math.abs(difficultyRank(item.difficulty()) - targetRank))
                .thenComparingInt(item -> difficultyRank(item.difficulty()))
                .thenComparing(ExerciseCatalogItem::title))
            .orElse(pointUnpassed.getFirst());
        return Optional.of(new RecommendationView(
            best.id(), best.title(), best.knowledgePoint(), best.difficulty(), best.exerciseType(), reason
        ));
    }

    private ExerciseCatalogItem toItem(ExerciseSummary summary, OwnerStatus status) {
        return new ExerciseCatalogItem(
            summary.id(), summary.title(), summary.knowledgePoint(), summary.difficulty(),
            summary.exerciseType(), summary.version(),
            status == null ? 0 : status.attempts(),
            status != null && status.passed(),
            status == null ? null : status.lastAttemptAt(),
            status == null ? null : status.bestScore()
        );
    }

    private Map<String, OwnerStatus> readOwnerStatus() {
        String owner = currentOwner();
        String sql = """
            select s.exercise_id,
                   count(a.id) as attempts,
                   max(case when a.status = 'PASSED' then 1 else 0 end) as passed,
                   max(a.created_at) as last_attempt_at,
                   max(a.score) as best_score
            from exercise_sessions s
            left join exercise_attempts a on a.session_id = s.id
            where s.owner_id = ?
            group by s.exercise_id
            """;
        try (Connection connection = connectionFactory.open("app");
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, owner);
            try (ResultSet rows = statement.executeQuery()) {
                Map<String, OwnerStatus> result = new HashMap<>();
                while (rows.next()) {
                    Integer bestScore = rows.getObject("best_score") == null ? null : rows.getInt("best_score");
                    result.put(rows.getString("exercise_id"), new OwnerStatus(
                        rows.getInt("attempts"), rows.getInt("passed") == 1,
                        rows.getString("last_attempt_at"), bestScore
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

    private String currentOwner() {
        String owner = ownerProvider.currentOwnerId();
        return owner == null || owner.isBlank() ? LearningEventOwnerProvider.GUEST_OWNER : owner.trim();
    }

    private static ExerciseType exerciseType(String raw) {
        if (raw == null || raw.isBlank()) {
            return ExerciseType.QUERY;
        }
        try {
            return ExerciseType.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException error) {
            return ExerciseType.QUERY;
        }
    }

    /** Joins the stored feedback lines into one bounded display string. */
    private static String firstFeedback(String feedbackJson) {
        if (feedbackJson == null || feedbackJson.isBlank()) {
            return "";
        }
        try {
            List<String> messages = FEEDBACK_MAPPER.readValue(
                feedbackJson, FEEDBACK_MAPPER.getTypeFactory().constructCollectionType(List.class, String.class)
            );
            String joined = String.join(" ", messages).strip();
            return joined.length() > 200 ? joined.substring(0, 200) + "…" : joined;
        } catch (JsonProcessingException | IllegalArgumentException error) {
            return "";
        }
    }

    private static int difficultyRank(ExerciseDifficulty difficulty) {
        return switch (difficulty) {
            case BEGINNER -> 0;
            case INTERMEDIATE -> 1;
            case ADVANCED -> 2;
        };
    }

    private record OwnerStatus(int attempts, boolean passed, String lastAttemptAt, Integer bestScore) {
    }
}

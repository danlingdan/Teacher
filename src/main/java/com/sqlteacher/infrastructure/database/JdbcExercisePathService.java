package com.sqlteacher.infrastructure.database;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlteacher.application.event.LearningEventOwnerProvider;
import com.sqlteacher.application.exercise.ExercisePathService;
import com.sqlteacher.domain.SqlTeacherException;
import com.sqlteacher.domain.exercise.ExerciseChapterPath;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * v3.5.0 EPATH-1: reads chapter paths stored by the exercise bank and joins them with the
 * current owner's per-exercise progress (attempts/passed) and the latest mastery snapshot
 * of each chapter's knowledge points. A path referencing an exercise that no longer exists
 * locally is skipped silently — the view only shows what the learner can actually open.
 */
public final class JdbcExercisePathService implements ExercisePathService {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcConnectionFactory connectionFactory;
    private final LearningEventOwnerProvider ownerProvider;

    public JdbcExercisePathService(JdbcConnectionFactory connectionFactory,
                                   LearningEventOwnerProvider ownerProvider) {
        this.connectionFactory = Objects.requireNonNull(connectionFactory);
        this.ownerProvider = Objects.requireNonNull(ownerProvider);
    }

    @Override
    public List<PathView> listPaths() {
        List<StoredPath> stored;
        try (Connection connection = connectionFactory.open("app")) {
            stored = readStoredPaths(connection);
        } catch (SQLException error) {
            throw new SqlTeacherException("EXERCISE_PATH_READ_FAILED", "无法读取学习路径。", error);
        }
        if (stored.isEmpty()) {
            return List.of();
        }
        try (Connection connection = connectionFactory.open("app")) {
            String owner = currentOwnerId();
            Set<String> exerciseIds = new HashSet<>();
            for (StoredPath path : stored) {
                for (ExerciseChapterPath.Chapter chapter : path.path().chapters()) {
                    exerciseIds.addAll(chapter.exerciseIds());
                }
            }
            Map<String, Progress> progressByExercise = readProgress(connection, owner, exerciseIds);
            Map<String, Integer> masteryByPoint = readMastery(connection, owner, progressByExercise);
            return buildViews(stored, progressByExercise, masteryByPoint);
        } catch (SQLException error) {
            throw new SqlTeacherException("EXERCISE_PATH_READ_FAILED", "无法读取学习路径。", error);
        }
    }

    private List<PathView> buildViews(
        List<StoredPath> stored,
        Map<String, Progress> progressByExercise,
        Map<String, Integer> masteryByPoint
    ) {
        List<PathView> views = new ArrayList<>(stored.size());
        for (StoredPath storedPath : stored) {
            List<ChapterView> chapters = new ArrayList<>(storedPath.path().chapters().size());
            for (ExerciseChapterPath.Chapter chapter : storedPath.path().chapters()) {
                List<PathExercise> exercises = new ArrayList<>(chapter.exerciseIds().size());
                for (String exerciseId : chapter.exerciseIds()) {
                    Progress progress = progressByExercise.get(exerciseId);
                    if (progress == null) {
                        continue;
                    }
                    exercises.add(new PathExercise(
                        exerciseId,
                        progress.title(),
                        progress.knowledgePoint(),
                        progress.attempts(),
                        progress.passed(),
                        masteryByPoint.get(progress.knowledgePoint())
                    ));
                }
                chapters.add(new ChapterView(chapter.order(), chapter.title(), chapter.knowledgeTags(), exercises));
            }
            views.add(new PathView(storedPath.path().id(), storedPath.path().name(), storedPath.path().version(), chapters));
        }
        return List.copyOf(views);
    }

    private record StoredPath(ExerciseChapterPath path) {
    }

    private List<StoredPath> readStoredPaths(Connection connection) throws SQLException {
        List<StoredPath> paths = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
            "select id, name, version, chapters_json from exercise_paths order by id");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                paths.add(new StoredPath(new ExerciseChapterPath(
                    rows.getString("id"),
                    rows.getString("name"),
                    rows.getInt("version"),
                    decodeChapters(rows.getString("chapters_json"))
                )));
            }
        }
        return paths;
    }

    private static List<ExerciseChapterPath.Chapter> decodeChapters(String json) throws SQLException {
        try {
            List<ChapterPayload> payload = JSON.readValue(json, new TypeReference<List<ChapterPayload>>() { });
            List<ExerciseChapterPath.Chapter> chapters = new ArrayList<>(payload.size());
            for (ChapterPayload item : payload) {
                chapters.add(new ExerciseChapterPath.Chapter(
                    item.order(), item.title(),
                    item.knowledgeTags() == null ? List.of() : item.knowledgeTags(),
                    item.exerciseIds() == null ? List.of() : item.exerciseIds()
                ));
            }
            return chapters;
        } catch (java.io.IOException error) {
            throw new SQLException("Stored chapter path JSON is invalid", error);
        }
    }

    private record ChapterPayload(
        int order, String title, List<String> knowledgeTags, List<String> exerciseIds
    ) {
    }

    private record Progress(String title, String knowledgePoint, int attempts, boolean passed) {
    }

    private Map<String, Progress> readProgress(Connection connection, String owner, Set<String> exerciseIds)
            throws SQLException {
        if (exerciseIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(exerciseIds.size(), "?"));
        String sql = """
            select e.id, e.title, e.knowledge_point,
                count(a.id) as attempts,
                coalesce(max(case when a.status = 'PASSED' then 1 else 0 end), 0) as passed
            from exercises e
            left join exercise_sessions s on s.exercise_id = e.id and s.owner_id = ?
            left join exercise_attempts a on a.session_id = s.id
            where e.id in (%s)
            group by e.id, e.title, e.knowledge_point
            """.formatted(placeholders);
        Map<String, Progress> progress = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            statement.setString(index++, owner);
            for (String id : exerciseIds) {
                statement.setString(index++, id);
            }
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    progress.put(rows.getString("id"), new Progress(
                        rows.getString("title"),
                        rows.getString("knowledge_point"),
                        rows.getInt("attempts"),
                        rows.getBoolean("passed")
                    ));
                }
            }
        }
        return progress;
    }

    /** Latest mastery snapshot per knowledge point; absent points simply have no entry. */
    private Map<String, Integer> readMastery(Connection connection, String owner, Map<String, Progress> progress)
            throws SQLException {
        Set<String> knowledgePoints = new HashSet<>();
        for (Progress progressItem : progress.values()) {
            knowledgePoints.add(progressItem.knowledgePoint());
        }
        if (knowledgePoints.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(knowledgePoints.size(), "?"));
        String sql = """
            select knowledge_point, mastery_percent, updated_at
            from mastery_snapshot
            where owner_id = ? and knowledge_point in (%s)
            order by knowledge_point, updated_at desc
            """.formatted(placeholders);
        Map<String, Integer> mastery = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            statement.setString(index++, owner);
            for (String point : knowledgePoints) {
                statement.setString(index++, point);
            }
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    mastery.putIfAbsent(rows.getString("knowledge_point"), rows.getInt("mastery_percent"));
                }
            }
        }
        return mastery;
    }

    private String currentOwnerId() {
        String owner = ownerProvider.currentOwnerId();
        return owner == null || owner.isBlank() ? LearningEventOwnerProvider.GUEST_OWNER : owner.trim();
    }
}

package com.sqlteacher.infrastructure.database;

import com.sqlteacher.application.event.LearningEventOwnerProvider;
import com.sqlteacher.application.event.LearningEventType;
import com.sqlteacher.application.learning.DiagnosisReasonCode;
import com.sqlteacher.application.learning.LearningAction;
import com.sqlteacher.application.learning.LearningActionType;
import com.sqlteacher.application.learning.LearningDashboard;
import com.sqlteacher.application.learning.LearningDiagnosisService;
import com.sqlteacher.application.learning.MasteryEvidence;
import com.sqlteacher.application.learning.MasteryLevel;
import com.sqlteacher.application.learning.MasterySnapshot;
import com.sqlteacher.domain.SqlTeacherException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Deterministic, owner-isolated learning diagnosis. Derived snapshots are safe to rebuild. */
public final class JdbcLearningDiagnosisService implements LearningDiagnosisService {
    public static final String POLICY_VERSION = "v1.7.0-r1";
    static final int MIN_EVIDENCE = 3;
    static final int MAX_ATTEMPTS_PER_POINT = 20;
    static final int MAX_ACTIONS = 7;
    private static final Duration WINDOW = Duration.ofDays(30);

    private final JdbcConnectionFactory connectionFactory;
    private final LearningEventOwnerProvider ownerProvider;
    private final Clock clock;

    public JdbcLearningDiagnosisService(JdbcConnectionFactory connectionFactory,
                                        LearningEventOwnerProvider ownerProvider) {
        this(connectionFactory, ownerProvider, Clock.systemUTC());
    }

    JdbcLearningDiagnosisService(JdbcConnectionFactory connectionFactory,
                                 LearningEventOwnerProvider ownerProvider,
                                 Clock clock) {
        this.connectionFactory = Objects.requireNonNull(connectionFactory);
        this.ownerProvider = Objects.requireNonNull(ownerProvider);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public LearningDashboard refresh() {
        long started = System.nanoTime();
        String ownerId = normalizedOwner();
        Instant now = clock.instant();
        try (Connection connection = connectionFactory.open("app")) {
            Map<String, ExerciseInfo> exercises = loadExercises(connection);
            List<EventRecord> events = loadOwnedEvents(connection, ownerId, now.minus(WINDOW));
            List<MasterySnapshot> snapshots = calculate(ownerId, exercises, events, now);
            persistSnapshots(connection, ownerId, snapshots);
            List<LearningAction> actions = buildActions(connection, ownerId, exercises, events, snapshots, now);
            return new LearningDashboard(ownerId, snapshots, actions, now,
                Duration.ofNanos(System.nanoTime() - started), POLICY_VERSION);
        } catch (SQLException error) {
            throw new SqlTeacherException("LEARNING_DIAGNOSIS_FAILED", "Failed to calculate learning diagnosis", error);
        }
    }

    @Override
    public void dismissAction(String actionId) {
        updateActionState(actionId, "DISMISSED");
    }

    @Override
    public void restoreAction(String actionId) {
        updateActionState(actionId, "OPEN");
    }

    @Override
    public boolean isActionDismissed(String actionId) {
        if (actionId == null || actionId.isBlank()) return false;
        try (Connection connection = connectionFactory.open("app")) {
            return "DISMISSED".equals(actionState(connection, actionId.trim()));
        } catch (SQLException error) {
            throw new SqlTeacherException("LEARNING_ACTION_READ_FAILED", "Failed to read learning action", error);
        }
    }

    @Override
    public String exportCsv() {
        LearningDashboard dashboard = refresh();
        StringBuilder csv = new StringBuilder("\ufeff知识点,掌握状态,尝试,通过,失败,提示,掌握度,原因,更新时间(UTC)\r\n");
        for (MasterySnapshot item : dashboard.mastery()) {
            row(csv, item.knowledgePoint(), item.level().name(), item.attempts(), item.passes(), item.failures(),
                item.hintsUsed(), item.masteryPercent() + "%",
                item.reasons().stream().map(Enum::name).toList().toString(), item.updatedAt());
        }
        return csv.toString();
    }

    private List<MasterySnapshot> calculate(String ownerId, Map<String, ExerciseInfo> exercises,
                                            List<EventRecord> events, Instant now) {
        Map<String, List<EventRecord>> attemptsByPoint = new LinkedHashMap<>();
        Map<String, Integer> hintsByPoint = new HashMap<>();
        for (EventRecord event : events) {
            ExerciseInfo exercise = exercises.get(event.exerciseId());
            if (exercise == null) continue;
            if (event.type() == LearningEventType.EXERCISE_HINT_USED) {
                hintsByPoint.merge(exercise.knowledgePoint(), 1, Integer::sum);
            } else if (event.type() == LearningEventType.EXERCISE_PASSED
                || event.type() == LearningEventType.EXERCISE_FAILED) {
                attemptsByPoint.computeIfAbsent(exercise.knowledgePoint(), ignored -> new ArrayList<>()).add(event);
            }
        }

        List<MasterySnapshot> result = new ArrayList<>();
        exercises.values().stream().map(ExerciseInfo::knowledgePoint).distinct().sorted().forEach(point -> {
            List<EventRecord> attempts = attemptsByPoint.getOrDefault(point, List.of()).stream()
                .sorted(Comparator.comparing(EventRecord::occurredAt).reversed().thenComparing(EventRecord::sourceId))
                .limit(MAX_ATTEMPTS_PER_POINT).toList();
            int passes = (int) attempts.stream().filter(EventRecord::successful).count();
            int failures = attempts.size() - passes;
            int hints = hintsByPoint.getOrDefault(point, 0);
            int percent = attempts.isEmpty() ? 0
                : Math.max(0, Math.min(100, (passes * 100 / attempts.size()) - Math.min(20, hints * 5)));
            MasteryLevel level;
            List<DiagnosisReasonCode> reasons = new ArrayList<>();
            if (attempts.size() < MIN_EVIDENCE) {
                level = MasteryLevel.UNKNOWN;
                reasons.add(DiagnosisReasonCode.INSUFFICIENT_EVIDENCE);
            } else if (percent <= 40 || consecutiveFailures(attempts) >= 2) {
                level = MasteryLevel.NEEDS_PRACTICE;
                reasons.add(DiagnosisReasonCode.REPEATED_FAILURE);
            } else if (percent < 80) {
                level = MasteryLevel.DEVELOPING;
                reasons.add(DiagnosisReasonCode.DEVELOPING_PROGRESS);
            } else {
                level = MasteryLevel.MASTERED;
                reasons.add(DiagnosisReasonCode.CONSISTENT_SUCCESS);
            }
            if (hints >= 2) reasons.add(DiagnosisReasonCode.HINT_DEPENDENCY);
            List<MasteryEvidence> evidence = attempts.stream().limit(5).map(event -> new MasteryEvidence(
                event.sourceId(), event.exerciseId(), event.type().name(), event.successful(), event.errorCode(),
                event.occurredAt())).toList();
            result.add(new MasterySnapshot(ownerId, point, level, attempts.size(), passes, failures, hints,
                percent, reasons, evidence, POLICY_VERSION, now));
        });
        return List.copyOf(result);
    }

    private List<LearningAction> buildActions(Connection connection, String ownerId,
                                               Map<String, ExerciseInfo> exercises,
                                               List<EventRecord> events,
                                               List<MasterySnapshot> snapshots,
                                               Instant now) throws SQLException {
        List<LearningAction> result = new ArrayList<>();
        for (ActiveSession session : loadActiveSessions(connection, ownerId)) {
            ExerciseInfo exercise = exercises.get(session.exerciseId());
            if (exercise == null) continue;
            String id = actionId(ownerId, LearningActionType.CONTINUE_EXERCISE, exercise.id(), session.id());
            result.add(action(connection, id, LearningActionType.CONTINUE_EXERCISE,
                "继续练习：" + exercise.title(), "上次练习尚未完成，继续完成当前题目。",
                exercise.id(), exercise.knowledgePoint(), DiagnosisReasonCode.INTERRUPTED_EXERCISE, 90,
                session.startedAt()));
        }
        for (MasterySnapshot snapshot : snapshots) {
            if (snapshot.level() != MasteryLevel.NEEDS_PRACTICE
                && snapshot.level() != MasteryLevel.DEVELOPING) continue;
            ExerciseInfo target = preferredExercise(exercises, events, snapshot.knowledgePoint());
            if (target == null) continue;
            DiagnosisReasonCode reason = snapshot.reasons().contains(DiagnosisReasonCode.REPEATED_FAILURE)
                ? DiagnosisReasonCode.REPEATED_FAILURE : DiagnosisReasonCode.DEVELOPING_PROGRESS;
            String cycle = evidenceHash(snapshot.evidence());
            String id = actionId(ownerId, LearningActionType.RETRY_EXERCISE, target.id(), cycle);
            result.add(action(connection, id, LearningActionType.RETRY_EXERCISE,
                "巩固：" + snapshot.knowledgePoint(), reason == DiagnosisReasonCode.REPEATED_FAILURE
                    ? "最近提交出现连续失败，建议重练“" + target.title() + "”。"
                    : "当前知识点正在形成掌握，建议再完成一道相关题目。",
                target.id(), snapshot.knowledgePoint(), reason,
                reason == DiagnosisReasonCode.REPEATED_FAILURE ? 75 : 55, now));
        }
        return result.stream().sorted(Comparator.comparingInt(LearningAction::priority).reversed()
                .thenComparing(LearningAction::updatedAt, Comparator.reverseOrder()).thenComparing(LearningAction::id))
            .filter(item -> !item.dismissed()).limit(MAX_ACTIONS).toList();
    }

    private LearningAction action(Connection connection, String id, LearningActionType type, String title,
                                  String description, String exerciseId, String point,
                                  DiagnosisReasonCode reason, int priority, Instant updatedAt) throws SQLException {
        return new LearningAction(id, type, title, description, exerciseId, point, reason, priority, updatedAt,
            "DISMISSED".equals(actionState(connection, id)));
    }

    private void updateActionState(String actionId, String state) {
        if (actionId == null || actionId.isBlank()) throw new IllegalArgumentException("actionId must not be blank");
        String ownerId = normalizedOwner();
        try (Connection connection = connectionFactory.open("app");
             PreparedStatement statement = connection.prepareStatement("""
                 insert into learning_action_state(action_id, owner_id, state, updated_at)
                 values (?, ?, ?, ?)
                 on conflict(action_id) do update set state=excluded.state, updated_at=excluded.updated_at
                 where learning_action_state.owner_id=excluded.owner_id
                 """)) {
            statement.setString(1, actionId.trim());
            statement.setString(2, ownerId);
            statement.setString(3, state);
            statement.setString(4, clock.instant().toString());
            statement.executeUpdate();
        } catch (SQLException error) {
            throw new SqlTeacherException("LEARNING_ACTION_UPDATE_FAILED", "Failed to update learning action", error);
        }
    }

    private static Map<String, ExerciseInfo> loadExercises(Connection connection) throws SQLException {
        Map<String, ExerciseInfo> result = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
            "select id,title,knowledge_point from exercises where enabled=1 order by knowledge_point,title");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                ExerciseInfo item = new ExerciseInfo(rows.getString(1), rows.getString(2), rows.getString(3));
                result.put(item.id(), item);
            }
        }
        return result;
    }

    private static List<EventRecord> loadOwnedEvents(Connection connection, String ownerId, Instant start)
        throws SQLException {
        List<EventRecord> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
            select id,event_type,occurred_at,successful,attributes from learning_events
            where occurred_at>=? and event_type in ('EXERCISE_PASSED','EXERCISE_FAILED','EXERCISE_HINT_USED')
            order by occurred_at desc,id desc
            """)) {
            statement.setString(1, start.toString());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    Map<String, String> attributes = LearningEventAttributesCodec.deserialize(rows.getString(5));
                    if (!ownerId.equals(attributes.getOrDefault(LearningEventOwnerProvider.OWNER_ATTRIBUTE,
                        LearningEventOwnerProvider.GUEST_OWNER))) continue;
                    String exerciseId = attributes.get("exerciseId");
                    if (exerciseId == null || exerciseId.isBlank()) continue;
                    result.add(new EventRecord(Long.toString(rows.getLong(1)),
                        LearningEventType.valueOf(rows.getString(2)), exerciseId, rows.getBoolean(4),
                        attributes.getOrDefault("errorCode", ""), Instant.parse(rows.getString(3))));
                }
            }
        }
        return List.copyOf(result);
    }

    private static List<ActiveSession> loadActiveSessions(Connection connection, String ownerId) throws SQLException {
        List<ActiveSession> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
            select id,exercise_id,started_at from exercise_sessions
            where completed_at is null and owner_id=? order by started_at desc
            """)) {
            statement.setString(1, ownerId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(new ActiveSession(rows.getString(1), rows.getString(2),
                    Instant.parse(rows.getString(3))));
            }
        }
        return List.copyOf(result);
    }

    private static void persistSnapshots(Connection connection, String ownerId, List<MasterySnapshot> snapshots)
        throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement(
            "delete from mastery_snapshot where owner_id=? and policy_version=?")) {
            delete.setString(1, ownerId);
            delete.setString(2, POLICY_VERSION);
            delete.executeUpdate();
        }
        try (PreparedStatement insert = connection.prepareStatement("""
            insert into mastery_snapshot(owner_id,knowledge_point,level,attempts,passes,failures,hints_used,
                mastery_percent,reason_codes,evidence_hash,policy_version,updated_at)
            values (?,?,?,?,?,?,?,?,?,?,?,?)
            """)) {
            for (MasterySnapshot item : snapshots) {
                insert.setString(1, item.ownerId()); insert.setString(2, item.knowledgePoint());
                insert.setString(3, item.level().name()); insert.setInt(4, item.attempts());
                insert.setInt(5, item.passes()); insert.setInt(6, item.failures());
                insert.setInt(7, item.hintsUsed()); insert.setInt(8, item.masteryPercent());
                insert.setString(9, item.reasons().stream().map(Enum::name).collect(java.util.stream.Collectors.joining(",")));
                insert.setString(10, evidenceHash(item.evidence())); insert.setString(11, item.policyVersion());
                insert.setString(12, item.updatedAt().toString()); insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private static String actionState(Connection connection, String id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "select state from learning_action_state where action_id=?")) {
            statement.setString(1, id);
            try (ResultSet row = statement.executeQuery()) { return row.next() ? row.getString(1) : "OPEN"; }
        }
    }

    private static ExerciseInfo preferredExercise(Map<String, ExerciseInfo> exercises, List<EventRecord> events,
                                                  String point) {
        for (EventRecord event : events) {
            ExerciseInfo item = exercises.get(event.exerciseId());
            if (item != null && item.knowledgePoint().equals(point) && !event.successful()) return item;
        }
        return exercises.values().stream().filter(item -> item.knowledgePoint().equals(point)).findFirst().orElse(null);
    }

    private static int consecutiveFailures(List<EventRecord> attempts) {
        int count = 0;
        for (EventRecord attempt : attempts) {
            if (attempt.successful()) break;
            count++;
        }
        return count;
    }

    private String normalizedOwner() {
        String owner = ownerProvider.currentOwnerId();
        return owner == null || owner.isBlank() ? LearningEventOwnerProvider.GUEST_OWNER : owner.trim();
    }

    private static String actionId(String owner, LearningActionType type, String target, String cycle) {
        return sha256(owner + "|" + type + "|" + target + "|" + cycle).substring(0, 32);
    }

    private static String evidenceHash(List<MasteryEvidence> evidence) {
        return sha256(evidence.stream().map(item -> item.sourceId() + ":" + item.kind())
            .collect(java.util.stream.Collectors.joining("|")));
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void row(StringBuilder csv, Object... values) {
        for (int index = 0; index < values.length; index++) {
            if (index > 0) csv.append(',');
            String value = String.valueOf(values[index]);
            if (!value.isEmpty() && "=+-@".indexOf(value.charAt(0)) >= 0) value = "'" + value;
            csv.append('"').append(value.replace("\"", "\"\"")).append('"');
        }
        csv.append("\r\n");
    }

    private record ExerciseInfo(String id, String title, String knowledgePoint) { }
    private record EventRecord(String sourceId, LearningEventType type, String exerciseId, boolean successful,
                               String errorCode, Instant occurredAt) { }
    private record ActiveSession(String id, String exerciseId, Instant startedAt) { }
}

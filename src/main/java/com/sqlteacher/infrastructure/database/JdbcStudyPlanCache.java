package com.sqlteacher.infrastructure.database;

import com.sqlteacher.application.event.LearningEventOwnerProvider;
import com.sqlteacher.application.planning.LearningEvidenceRef;
import com.sqlteacher.application.planning.LearningEvidenceType;
import com.sqlteacher.application.planning.ObjectiveResourceType;
import com.sqlteacher.application.planning.StudyPlanAction;
import com.sqlteacher.application.planning.StudyPlanActionState;
import com.sqlteacher.application.planning.StudyPlanActionType;
import com.sqlteacher.application.planning.StudyPlanCache;
import com.sqlteacher.application.planning.StudyPlanChange;
import com.sqlteacher.application.planning.StudyPlanChangeType;
import com.sqlteacher.application.planning.StudyPlanReasonCode;
import com.sqlteacher.application.planning.StudyPlanRefresh;
import com.sqlteacher.application.planning.StudyPlanSnapshot;
import com.sqlteacher.application.planning.PlanSyncOperation;
import com.sqlteacher.domain.SqlTeacherException;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Owner-isolated local plan snapshot, lifecycle and outbox persistence. */
public final class JdbcStudyPlanCache implements StudyPlanCache {
    private final JdbcConnectionFactory connections;
    private final LearningEventOwnerProvider owners;
    private final Clock clock;

    public JdbcStudyPlanCache(JdbcConnectionFactory connections, LearningEventOwnerProvider owners) {
        this(connections, owners, Clock.systemUTC());
    }

    JdbcStudyPlanCache(JdbcConnectionFactory connections, LearningEventOwnerProvider owners, Clock clock) {
        this.connections = Objects.requireNonNull(connections);
        this.owners = Objects.requireNonNull(owners);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public void saveObjectives(String courseId, List<com.sqlteacher.application.planning.CourseObjective> objectives) {
        ensureCompatibilityColumns();
        if (courseId == null || courseId.isBlank()) throw new IllegalArgumentException("courseId must not be blank");
        List<com.sqlteacher.application.planning.CourseObjective> values = objectives == null ? List.of()
            : objectives.stream().filter(item -> courseId.equals(item.courseId())).toList();
        try (Connection connection = connections.open("app")) {
            connection.setAutoCommit(false);
            try (PreparedStatement delete = connection.prepareStatement(
                "delete from course_objective_cache where account_id=? and course_id=?")) {
                delete.setString(1, owner()); delete.setString(2, courseId); delete.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement("""
                insert into course_objective_cache(account_id,course_id,objective_id,title,description,
                    completion_criteria,sort_order,status,version,updated_at) values(?,?,?,?,?,?,?,?,?,?)
                """)) {
                for (var item : values) {
                    insert.setString(1, owner()); insert.setString(2, courseId); insert.setString(3, item.id());
                    insert.setString(4, item.title()); insert.setString(5, item.description());
                    insert.setString(6, item.completionCriteria()); insert.setInt(7, item.sortOrder());
                    insert.setString(8, item.status().name()); insert.setLong(9, item.version());
                    insert.setString(10, item.updatedAt().toString()); insert.addBatch();
                }
                insert.executeBatch(); connection.commit();
            } catch (SQLException | RuntimeException error) {
                connection.rollback(); throw error;
            }
        } catch (SQLException error) {
            throw database("PLAN_OBJECTIVE_CACHE_FAILED", error);
        }
    }

    @Override
    public StudyPlanRefresh save(StudyPlanSnapshot requested) {
        ensureCompatibilityColumns();
        Objects.requireNonNull(requested, "snapshot must not be null");
        String owner = owner();
        if (!owner.equals(requested.ownerId())) throw new SecurityException("Study plan owner mismatch");
        try (Connection connection = connections.open("app")) {
            connection.setAutoCommit(false);
            try {
                StudyPlanSnapshot previous = current(connection, owner, requested.courseId());
                List<StudyPlanChange> changes = changes(previous, requested);
                String snapshotId = stableSnapshotId(requested);
                boolean sameFacts = previous != null
                    && previous.factWatermark().equals(requested.factWatermark());
                if (sameFacts) deleteActiveSnapshotRows(connection, owner, requested.courseId());
                String retireSql = sameFacts
                    ? "delete from study_plan_snapshot where owner_id=? and course_id=? and status='ACTIVE'"
                    : "update study_plan_snapshot set status='INVALIDATED' where owner_id=? and course_id=? and status='ACTIVE'";
                try (PreparedStatement statement = connection.prepareStatement(retireSql)) {
                    statement.setString(1, owner); statement.setString(2, requested.courseId()); statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                    insert into study_plan_snapshot(id,owner_id,course_id,policy_version,fact_watermark,
                        generated_at,expires_at,status) values(?,?,?,?,?,?,?,'ACTIVE')
                    """)) {
                    statement.setString(1, snapshotId); statement.setString(2, owner);
                    statement.setString(3, requested.courseId()); statement.setString(4, requested.policyVersion());
                    statement.setString(5, requested.factWatermark()); statement.setString(6, requested.generatedAt().toString());
                    statement.setString(7, requested.expiresAt().toString()); statement.executeUpdate();
                }
                Map<String, StudyPlanActionState> priorStates = previous == null || sameFacts ? Map.of()
                    : previous.actions().stream().collect(java.util.stream.Collectors.toMap(StudyPlanAction::id,
                    StudyPlanAction::state));
                for (StudyPlanAction action : requested.actions()) {
                    StudyPlanActionState state = priorStates.getOrDefault(action.id(), action.state());
                    insertAction(connection, snapshotId, action, state, requested.generatedAt());
                    try (PreparedStatement resource = connection.prepareStatement("""
                        insert or replace into objective_resource_cache(account_id,course_id,objective_id,
                            resource_type,resource_id,updated_at) values(?,?,?,?,?,?)
                        """)) {
                        resource.setString(1, owner); resource.setString(2, requested.courseId());
                        resource.setString(3, action.objectiveId()); resource.setString(4, action.resourceType().name());
                        resource.setString(5, action.resourceId()); resource.setString(6, requested.generatedAt().toString());
                        resource.executeUpdate();
                    }
                }
                connection.commit();
                return new StudyPlanRefresh(withStates(requested, priorStates), changes);
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                throw error;
            }
        } catch (SQLException error) {
            throw database("PLAN_CACHE_SAVE_FAILED", error);
        }
    }

    @Override
    public List<StudyPlanSnapshot> currentPlans() {
        ensureCompatibilityColumns();
        try (Connection connection = connections.open("app"); PreparedStatement statement = connection.prepareStatement(
            "select distinct course_id from study_plan_snapshot where owner_id=? and status='ACTIVE' order by course_id")) {
            statement.setString(1, owner());
            List<StudyPlanSnapshot> result = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    StudyPlanSnapshot plan = current(connection, owner(), rows.getString(1));
                    if (plan != null) result.add(plan);
                }
            }
            return List.copyOf(result);
        } catch (SQLException error) {
            throw database("PLAN_CACHE_READ_FAILED", error);
        }
    }

    @Override
    public PlanSyncOperation updateAction(String courseId, String actionId, StudyPlanActionState state) {
        ensureCompatibilityColumns();
        if (courseId == null || courseId.isBlank() || actionId == null || actionId.isBlank() || state == null
            || state == StudyPlanActionState.OPEN || state == StudyPlanActionState.INVALIDATED) {
            throw new IllegalArgumentException("Invalid study plan action transition");
        }
        Instant now = clock.instant();
        try (Connection connection = connections.open("app")) {
            connection.setAutoCommit(false);
            try (PreparedStatement update = connection.prepareStatement("""
                update study_plan_action set state=?,updated_at=? where action_key=? and snapshot_id in
                    (select id from study_plan_snapshot where owner_id=? and course_id=? and status='ACTIVE')
                """)) {
                update.setString(1, state.name()); update.setString(2, now.toString()); update.setString(3, actionId);
                update.setString(4, owner()); update.setString(5, courseId);
                if (update.executeUpdate() != 1) throw new IllegalArgumentException("Study plan action is not active");
                String operationId = UUID.nameUUIDFromBytes((owner() + ':' + actionId + ':' + state)
                    .getBytes(StandardCharsets.UTF_8)).toString();
                try (PreparedStatement outbox = connection.prepareStatement("""
                    insert or ignore into study_plan_outbox(operation_id,owner_id,action_id,requested_state,status,
                        attempt_count,next_attempt_at,last_error_code,created_at,updated_at)
                    values(?,?,?,?,'PENDING',0,?,null,?,?)
                    """)) {
                    outbox.setString(1, operationId); outbox.setString(2, owner()); outbox.setString(3, actionId);
                    outbox.setString(4, state.name()); outbox.setString(5, now.toString());
                    outbox.setString(6, now.toString()); outbox.setString(7, now.toString()); outbox.executeUpdate();
                }
                long expectedVersion = actionVersion(connection, owner(), courseId, actionId);
                connection.commit();
                return new PlanSyncOperation(operationId, courseId, actionId, state, expectedVersion, 0);
            } catch (SQLException | RuntimeException error) {
                connection.rollback(); throw error;
            }
        } catch (SQLException error) {
            throw database("PLAN_ACTION_UPDATE_FAILED", error);
        }
    }

    @Override
    public int pendingOperations() {
        ensureCompatibilityColumns();
        try (Connection connection = connections.open("app"); PreparedStatement statement = connection.prepareStatement(
            "select count(*) from study_plan_outbox where owner_id=? and status='PENDING'")) {
            statement.setString(1, owner());
            try (ResultSet row = statement.executeQuery()) { return row.next() ? row.getInt(1) : 0; }
        } catch (SQLException error) {
            throw database("PLAN_OUTBOX_READ_FAILED", error);
        }
    }

    @Override
    public List<PlanSyncOperation> pending() {
        ensureCompatibilityColumns();
        try (Connection connection = connections.open("app"); PreparedStatement statement = connection.prepareStatement("""
            select o.operation_id,s.course_id,o.action_id,o.requested_state,a.sync_version,o.attempt_count
            from study_plan_outbox o join study_plan_action a on a.action_key=o.action_id
            join study_plan_snapshot s on s.id=a.snapshot_id
            where o.owner_id=? and o.status='PENDING' and o.next_attempt_at<=? and s.status='ACTIVE'
            order by o.created_at limit 100
            """)) {
            statement.setString(1, owner()); statement.setString(2, clock.instant().toString());
            List<PlanSyncOperation> result = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(new PlanSyncOperation(rows.getString(1), rows.getString(2),
                    rows.getString(3), StudyPlanActionState.valueOf(rows.getString(4)), rows.getLong(5),
                    rows.getInt(6)));
            }
            return List.copyOf(result);
        } catch (SQLException error) {
            throw database("PLAN_OUTBOX_READ_FAILED", error);
        }
    }

    @Override
    public void markDelivered(String operationId, String actionId, long serverVersion) {
        ensureCompatibilityColumns();
        try (Connection connection = connections.open("app")) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(
                "update study_plan_outbox set status='DELIVERED',updated_at=? where owner_id=? and operation_id=? and status='PENDING'")) {
                statement.setString(1, clock.instant().toString()); statement.setString(2, owner());
                statement.setString(3, operationId); statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                update study_plan_action set sync_version=? where action_key=? and snapshot_id in
                    (select id from study_plan_snapshot where owner_id=? and status='ACTIVE')
                """)) {
                statement.setLong(1, serverVersion); statement.setString(2, actionId);
                statement.setString(3, owner()); statement.executeUpdate();
            }
            connection.commit();
        } catch (SQLException error) {
            throw database("PLAN_OUTBOX_UPDATE_FAILED", error);
        }
    }

    @Override
    public void markFailed(String operationId, String errorCode, boolean retryable) {
        ensureCompatibilityColumns();
        Instant now = clock.instant();
        try (Connection connection = connections.open("app"); PreparedStatement statement = connection.prepareStatement("""
            update study_plan_outbox set status=?,attempt_count=attempt_count+1,next_attempt_at=?,
                last_error_code=?,updated_at=? where owner_id=? and operation_id=? and status='PENDING'
            """)) {
            statement.setString(1, retryable ? "PENDING" : "REJECTED");
            statement.setString(2, now.plusSeconds(retryable ? 30 : 0).toString());
            statement.setString(3, errorCode == null ? "UNKNOWN" : errorCode);
            statement.setString(4, now.toString()); statement.setString(5, owner());
            statement.setString(6, operationId); statement.executeUpdate();
        } catch (SQLException error) {
            throw database("PLAN_OUTBOX_UPDATE_FAILED", error);
        }
    }

    private long actionVersion(Connection connection, String owner, String courseId, String actionId)
        throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            select a.sync_version from study_plan_action a join study_plan_snapshot s on s.id=a.snapshot_id
            where s.owner_id=? and s.course_id=? and s.status='ACTIVE' and a.action_key=?
            """)) {
            statement.setString(1, owner); statement.setString(2, courseId); statement.setString(3, actionId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new IllegalArgumentException("Study plan action is not active");
                return row.getLong(1);
            }
        }
    }

    private void deleteActiveSnapshotRows(Connection connection, String owner, String courseId) throws SQLException {
        String active = "select id from study_plan_snapshot where owner_id=? and course_id=? and status='ACTIVE'";
        try (PreparedStatement evidence = connection.prepareStatement(
            "delete from study_plan_evidence where action_id in (select id from study_plan_action where snapshot_id in ("
                + active + "))")) {
            evidence.setString(1, owner); evidence.setString(2, courseId); evidence.executeUpdate();
        }
        try (PreparedStatement actions = connection.prepareStatement(
            "delete from study_plan_action where snapshot_id in (" + active + ")")) {
            actions.setString(1, owner); actions.setString(2, courseId); actions.executeUpdate();
        }
    }

    private StudyPlanSnapshot current(Connection connection, String owner, String courseId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            select * from study_plan_snapshot where owner_id=? and course_id=? and status='ACTIVE'
            order by generated_at desc limit 1
            """)) {
            statement.setString(1, owner); statement.setString(2, courseId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return null;
                Instant expiresAt = Instant.parse(row.getString("expires_at"));
                if (!expiresAt.isAfter(clock.instant())) {
                    try (PreparedStatement expire = connection.prepareStatement(
                        "update study_plan_snapshot set status='EXPIRED' where id=?")) {
                        expire.setString(1, row.getString("id")); expire.executeUpdate();
                    }
                    return null;
                }
                return new StudyPlanSnapshot(owner, courseId, row.getString("policy_version"),
                    row.getString("fact_watermark"), Instant.parse(row.getString("generated_at")), expiresAt,
                    actions(connection, row.getString("id")));
            }
        }
    }

    private List<StudyPlanAction> actions(Connection connection, String snapshotId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "select * from study_plan_action where snapshot_id=? and state<>'INVALIDATED' order by priority desc,id")) {
            statement.setString(1, snapshotId);
            List<StudyPlanAction> result = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(new StudyPlanAction(rows.getString("action_key"), rows.getString("objective_id"),
                    StudyPlanActionType.valueOf(rows.getString("action_type")), rows.getString("title"),
                    rows.getString("description"), ObjectiveResourceType.valueOf(rows.getString("resource_type")),
                    rows.getString("resource_id"), StudyPlanReasonCode.valueOf(rows.getString("reason_code")),
                    rows.getInt("priority"), StudyPlanActionState.valueOf(rows.getString("state")),
                    rows.getString("resolution_condition"), evidence(connection, rows.getString("id")),
                    rows.getLong("sync_version")));
            }
            return List.copyOf(result);
        }
    }

    private List<LearningEvidenceRef> evidence(Connection connection, String actionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "select * from study_plan_evidence where action_id=? order by evidence_type,evidence_id")) {
            statement.setString(1, actionId); List<LearningEvidenceRef> result = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(new LearningEvidenceRef(LearningEvidenceType.valueOf(rows.getString(2)),
                    rows.getString(3), rows.getString(4), rows.getString(5), Instant.EPOCH));
            }
            return List.copyOf(result);
        }
    }

    private void insertAction(Connection connection, String snapshotId, StudyPlanAction action,
                              StudyPlanActionState state, Instant updatedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            insert into study_plan_action(id,snapshot_id,action_key,objective_id,action_type,title,description,resource_type,
                resource_id,reason_code,resolution_condition,priority,state,sync_version,updated_at) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """)) {
            statement.setString(1, snapshotId + ':' + action.id()); statement.setString(2, snapshotId);
            statement.setString(3, action.id()); statement.setString(4, action.objectiveId());
            statement.setString(5, action.type().name()); statement.setString(6, action.title());
            statement.setString(7, action.description()); statement.setString(8, action.resourceType().name());
            statement.setString(9, action.resourceId()); statement.setString(10, action.reasonCode().name());
            statement.setString(11, action.resolutionCondition()); statement.setInt(12, action.priority());
            statement.setString(13, state.name()); statement.setLong(14, action.stateVersion());
            statement.setString(15, updatedAt.toString()); statement.executeUpdate();
        }
        for (LearningEvidenceRef item : action.evidence()) {
            try (PreparedStatement statement = connection.prepareStatement("""
                insert into study_plan_evidence(action_id,evidence_type,evidence_id,evidence_version,evidence_hash)
                values(?,?,?,?,?)
                """)) {
                statement.setString(1, snapshotId + ':' + action.id()); statement.setString(2, item.type().name());
                statement.setString(3, item.evidenceId()); statement.setString(4, item.version());
                statement.setString(5, item.contentHash()); statement.executeUpdate();
            }
        }
    }

    private static List<StudyPlanChange> changes(StudyPlanSnapshot before, StudyPlanSnapshot after) {
        if (before == null) return after.actions().stream().map(action -> new StudyPlanChange(action.id(),
            StudyPlanChangeType.ADDED, "新增学习动作：" + action.reasonCode())).toList();
        Map<String, StudyPlanAction> old = before.actions().stream()
            .collect(java.util.stream.Collectors.toMap(StudyPlanAction::id, item -> item));
        Map<String, StudyPlanAction> next = after.actions().stream()
            .collect(java.util.stream.Collectors.toMap(StudyPlanAction::id, item -> item));
        List<StudyPlanChange> result = new ArrayList<>();
        next.forEach((id, action) -> {
            if (!old.containsKey(id)) result.add(new StudyPlanChange(id, StudyPlanChangeType.ADDED,
                "新增学习动作：" + action.reasonCode()));
            else if (old.get(id).priority() != action.priority()) result.add(new StudyPlanChange(id,
                StudyPlanChangeType.PRIORITY_CHANGED, "优先级随事实变化由 " + old.get(id).priority() + " 调整为 " + action.priority()));
        });
        old.keySet().stream().filter(id -> !next.containsKey(id)).forEach(id -> result.add(new StudyPlanChange(id,
            StudyPlanChangeType.RESOLVED, "新的有效事实已解除该动作")));
        return List.copyOf(result);
    }

    private static StudyPlanSnapshot withStates(StudyPlanSnapshot plan, Map<String, StudyPlanActionState> states) {
        List<StudyPlanAction> actions = plan.actions().stream().map(action -> new StudyPlanAction(action.id(),
            action.objectiveId(), action.type(), action.title(), action.description(), action.resourceType(),
            action.resourceId(), action.reasonCode(), action.priority(), states.getOrDefault(action.id(), action.state()),
            action.resolutionCondition(), action.evidence(), action.stateVersion())).toList();
        return new StudyPlanSnapshot(plan.ownerId(), plan.courseId(), plan.policyVersion(), plan.factWatermark(),
            plan.generatedAt(), plan.expiresAt(), actions);
    }

    private static String stableSnapshotId(StudyPlanSnapshot plan) {
        return UUID.nameUUIDFromBytes((plan.ownerId() + ':' + plan.courseId() + ':' + plan.policyVersion() + ':'
            + plan.factWatermark()).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private String owner() {
        String value = owners.currentOwnerId();
        return value == null || value.isBlank() ? LearningEventOwnerProvider.GUEST_OWNER : value.trim();
    }

    private void ensureCompatibilityColumns() {
        try (Connection connection = connections.open("app"); Statement statement = connection.createStatement()) {
            addColumn(statement, "alter table study_plan_action add column title text not null default '学习动作'");
            addColumn(statement, "alter table study_plan_action add column description text not null default '根据课程目标继续学习。'");
            addColumn(statement, "alter table study_plan_action add column resolution_condition text not null default '产生新的有效证据'");
            addColumn(statement, "alter table study_plan_action add column action_key text not null default ''");
            addColumn(statement, "alter table study_plan_action add column sync_version integer not null default 0");
            statement.executeUpdate("update study_plan_action set action_key=id where action_key=''");
            statement.executeUpdate("create unique index if not exists study_plan_action_key on study_plan_action(snapshot_id,action_key)");
        } catch (SQLException error) {
            throw database("PLAN_CACHE_INIT_FAILED", error);
        }
    }

    private static void addColumn(Statement statement, String sql) throws SQLException {
        try { statement.executeUpdate(sql); }
        catch (SQLException error) { if (!error.getMessage().toLowerCase(java.util.Locale.ROOT).contains("duplicate column")) throw error; }
    }

    private static SqlTeacherException database(String code, SQLException error) {
        return new SqlTeacherException(code, "Study plan database operation failed", error);
    }
}

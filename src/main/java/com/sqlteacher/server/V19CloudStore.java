package com.sqlteacher.server;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlteacher.application.collaboration.AuthenticatedUser;
import com.sqlteacher.application.collaboration.ContentStatus;
import com.sqlteacher.application.collaboration.UserRole;
import com.sqlteacher.application.planning.CourseObjective;
import com.sqlteacher.application.planning.DeterministicStudyPlanService;
import com.sqlteacher.application.planning.ObjectivePrerequisite;
import com.sqlteacher.application.planning.ObjectiveResourceLink;
import com.sqlteacher.application.planning.ObjectiveResourceType;
import com.sqlteacher.application.planning.StudyPlanSnapshot;
import com.sqlteacher.application.planning.StudyPlanAction;
import com.sqlteacher.application.planning.StudyPlanActionState;
import com.sqlteacher.application.planning.StudyPlanActionStateRecord;
import com.sqlteacher.application.planning.ObjectiveClassSummary;
import com.sqlteacher.application.planning.ObjectiveInterventionDraft;
import com.sqlteacher.application.planning.PlanningHealthSummary;
import com.sqlteacher.application.collaboration.KnowledgeMastery;
import com.sqlteacher.domain.SqlTeacherException;

import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** v1.9 course objective graph and deterministic study-plan persistence. */
final class V19CloudStore {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Path database;
    private final DeterministicStudyPlanService plans = new DeterministicStudyPlanService();

    V19CloudStore(Path database) throws SQLException {
        this.database = Objects.requireNonNull(database, "database must not be null").toAbsolutePath().normalize();
        initialize();
    }

    CourseObjective createObjective(AuthenticatedUser actor, String courseId, String title, String description,
                                    String completionCriteria, int sortOrder) {
        requireCourseOwner(actor, courseId);
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        CourseObjective objective = new CourseObjective(id, courseId, title, description, completionCriteria,
            sortOrder, ContentStatus.ACTIVE, 1, actor.id(), now, now);
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement("""
            insert into course_objectives(id,course_id,title,description,completion_criteria,sort_order,status,
                version,created_by,created_at,updated_at) values(?,?,?,?,?,?,'ACTIVE',1,?,?,?)
            """)) {
            statement.setString(1, objective.id());
            statement.setString(2, objective.courseId());
            statement.setString(3, objective.title());
            statement.setString(4, objective.description());
            statement.setString(5, objective.completionCriteria());
            statement.setInt(6, objective.sortOrder());
            statement.setString(7, objective.createdBy());
            statement.setString(8, objective.createdAt().toString());
            statement.setString(9, objective.updatedAt().toString());
            statement.executeUpdate();
            return objective;
        } catch (SQLException error) {
            throw database(error);
        }
    }

    List<CourseObjective> listObjectives(AuthenticatedUser actor, String courseId) {
        boolean owner = requireCourseReadable(actor, courseId);
        String sql = "select * from course_objectives where course_id=?"
            + (owner ? "" : " and status='ACTIVE'") + " order by sort_order,id";
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, courseId);
            List<CourseObjective> result = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(objective(rows));
            }
            return List.copyOf(result);
        } catch (SQLException error) {
            throw database(error);
        }
    }

    CourseObjective updateObjective(AuthenticatedUser actor, String courseId, String objectiveId, String title,
                                    String description, String completionCriteria, int sortOrder,
                                    ContentStatus status, long expectedVersion) {
        requireCourseOwner(actor, courseId);
        Instant now = Instant.now();
        CourseObjective validated = new CourseObjective(objectiveId, courseId, title, description,
            completionCriteria, sortOrder, status, expectedVersion, actor.id(), now, now);
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement("""
            update course_objectives set title=?,description=?,completion_criteria=?,sort_order=?,status=?,
                version=version+1,updated_at=? where id=? and course_id=? and version=?
            """)) {
            statement.setString(1, validated.title()); statement.setString(2, validated.description());
            statement.setString(3, validated.completionCriteria()); statement.setInt(4, validated.sortOrder());
            statement.setString(5, validated.status().name()); statement.setString(6, now.toString());
            statement.setString(7, objectiveId); statement.setString(8, courseId);
            statement.setLong(9, expectedVersion);
            if (statement.executeUpdate() != 1) {
                if (objectiveExists(connection, courseId, objectiveId)) {
                    throw new V19VersionConflictException("Course objective version changed; refresh before saving");
                }
                throw new IllegalArgumentException("Course objective not found");
            }
            return findObjective(connection, objectiveId);
        } catch (SQLException error) {
            throw database(error);
        }
    }

    ObjectivePrerequisite addPrerequisite(AuthenticatedUser actor, String courseId, String objectiveId,
                                          String prerequisiteObjectiveId) {
        requireCourseOwner(actor, courseId);
        Instant now = Instant.now();
        ObjectivePrerequisite prerequisite = new ObjectivePrerequisite(objectiveId, prerequisiteObjectiveId, now);
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                requireObjective(connection, courseId, objectiveId);
                requireObjective(connection, courseId, prerequisiteObjectiveId);
                if (createsCycle(connection, objectiveId, prerequisiteObjectiveId)) {
                    throw new IllegalArgumentException("Objective prerequisite would create a cycle");
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                    insert or ignore into objective_prerequisites(objective_id,prerequisite_objective_id,created_at)
                    values(?,?,?)
                    """)) {
                    statement.setString(1, objectiveId);
                    statement.setString(2, prerequisiteObjectiveId);
                    statement.setString(3, now.toString());
                    statement.executeUpdate();
                }
                connection.commit();
                return prerequisite;
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                throw error;
            }
        } catch (SQLException error) {
            throw database(error);
        }
    }

    ObjectiveResourceLink addResource(AuthenticatedUser actor, String courseId, String objectiveId,
                                      ObjectiveResourceType resourceType, String resourceId) {
        requireCourseOwner(actor, courseId);
        Instant now = Instant.now();
        ObjectiveResourceLink link = new ObjectiveResourceLink(objectiveId, resourceType, resourceId, now);
        try (Connection connection = open()) {
            requireObjective(connection, courseId, objectiveId);
            requireResource(connection, courseId, resourceType, resourceId);
            try (PreparedStatement statement = connection.prepareStatement("""
                insert or ignore into objective_resource_links(objective_id,resource_type,resource_id,created_at)
                values(?,?,?,?)
                """)) {
                statement.setString(1, objectiveId);
                statement.setString(2, resourceType.name());
                statement.setString(3, resourceId);
                statement.setString(4, now.toString());
                statement.executeUpdate();
            }
            return link;
        } catch (SQLException error) {
            throw database(error);
        }
    }

    StudyPlanSnapshot studyPlan(AuthenticatedUser actor, String courseId) {
        boolean owner = requireCourseReadable(actor, courseId);
        List<CourseObjective> objectives = listObjectives(actor, courseId).stream()
            .filter(item -> item.status() == ContentStatus.ACTIVE).toList();
        Set<String> objectiveIds = objectives.stream().map(CourseObjective::id)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (objectiveIds.isEmpty()) {
            return plans.generate(actor.id(), courseId, objectives, List.of(), List.of(), Set.of(), Instant.now());
        }
        try (Connection connection = open()) {
            List<ObjectivePrerequisite> prerequisites = prerequisites(connection, objectiveIds);
            List<ObjectiveResourceLink> resources = resources(connection, objectiveIds, owner);
            StudyPlanSnapshot generated = plans.generate(actor.id(), courseId, objectives, prerequisites, resources,
                Set.of(), Instant.now());
            return applyStates(connection, generated);
        } catch (SQLException error) {
            throw database(error);
        }
    }

    StudyPlanActionStateRecord updateActionState(AuthenticatedUser actor, String courseId, String actionId,
                                                 StudyPlanActionState state, long expectedVersion,
                                                 String operationId) {
        if (state == StudyPlanActionState.OPEN || state == StudyPlanActionState.INVALIDATED) {
            throw new IllegalArgumentException("Only STARTED, COMPLETED or DISMISSED can be synchronized");
        }
        String normalizedOperationId = required(operationId, "operationId", 80);
        requireCourseReadable(actor, courseId);
        if (studyPlan(actor, courseId).actions().stream().noneMatch(action -> action.id().equals(actionId))) {
            throw new IllegalArgumentException("Study plan action is no longer active");
        }
        Instant now = Instant.now();
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                StudyPlanActionStateRecord current = actionState(connection, actor.id(), courseId, actionId);
                if (operationExists(connection, actor.id(), normalizedOperationId)) {
                    connection.rollback();
                    return current == null ? new StudyPlanActionStateRecord(actor.id(), courseId, actionId,
                        state, 1, now) : current;
                }
                long currentVersion = current == null ? 0 : current.version();
                if (expectedVersion != currentVersion) {
                    throw new V19VersionConflictException("Study plan action version changed; refresh before retrying");
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                    insert into study_plan_action_state(owner_id,action_id,course_id,requested_state,version,updated_at)
                    values(?,?,?,?,?,?) on conflict(owner_id,action_id) do update set
                    requested_state=excluded.requested_state,version=excluded.version,updated_at=excluded.updated_at
                    """)) {
                    statement.setString(1, actor.id()); statement.setString(2, actionId);
                    statement.setString(3, courseId); statement.setString(4, state.name());
                    statement.setLong(5, currentVersion + 1); statement.setString(6, now.toString());
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                    insert into study_plan_sync_operations(owner_id,operation_id,status,created_at)
                    values(?,?,'DELIVERED',?)
                    """)) {
                    statement.setString(1, actor.id()); statement.setString(2, normalizedOperationId);
                    statement.setString(3, now.toString()); statement.executeUpdate();
                }
                connection.commit();
                return new StudyPlanActionStateRecord(actor.id(), courseId, actionId, state,
                    currentVersion + 1, now);
            } catch (SQLException | RuntimeException error) {
                connection.rollback(); throw error;
            }
        } catch (SQLException error) {
            throw database(error);
        }
    }

    List<ObjectiveClassSummary> objectiveSummaries(AuthenticatedUser actor, String courseId, String classroomId,
                                                    V14CloudStore knowledge) {
        requireCourseOwner(actor, courseId);
        requireClassTeacher(actor, classroomId);
        List<CourseObjective> objectives = listObjectives(actor, courseId).stream()
            .filter(item -> item.status() == ContentStatus.ACTIVE).toList();
        try (Connection connection = open()) {
            List<String> students = classroomStudents(connection, classroomId);
            Map<String, Set<String>> pointsByObjective = new java.util.LinkedHashMap<>();
            objectives.forEach(objective -> {
                try { pointsByObjective.put(objective.id(), objectiveKnowledgePoints(connection, objective.id())); }
                catch (SQLException error) { throw database(error); }
            });
            Map<String, Map<String, EvidenceTotals>> totals = objectiveEvidence(connection, classroomId,
                pointsByObjective);
            List<ObjectiveClassSummary> result = new ArrayList<>();
            for (CourseObjective objective : objectives) {
                int unknown = 0, support = 0, developing = 0, mastered = 0;
                for (String student : students) {
                    EvidenceTotals evidence = totals.getOrDefault(objective.id(), Map.of()).get(student);
                    if (evidence == null || evidence.attempts == 0) unknown++;
                    else {
                        int percent = evidence.passes * 100 / evidence.attempts;
                        if (percent <= 40) support++;
                        else if (percent < 80) developing++;
                        else mastered++;
                    }
                }
                result.add(new ObjectiveClassSummary(classroomId, objective.id(), objective.title(), students.size(),
                    unknown, support, developing, mastered, Instant.now()));
            }
            return List.copyOf(result);
        } catch (SQLException error) {
            throw database(error);
        }
    }

    ObjectiveInterventionDraft createInterventionDraft(AuthenticatedUser actor, String courseId,
                                                        String classroomId, String objectiveId,
                                                        String reasonCode, String action, V14CloudStore knowledge) {
        requireCourseOwner(actor, courseId);
        requireClassTeacher(actor, classroomId);
        CourseObjective objective;
        try (Connection connection = open()) {
            requireObjective(connection, courseId, objectiveId);
            objective = findObjective(connection, objectiveId);
        } catch (SQLException error) {
            throw database(error);
        }
        String reason = required(reasonCode, "reasonCode", 80);
        String normalizedAction = required(action, "action", 500);
        int impact = objectiveSummaries(actor, courseId, classroomId, knowledge).stream()
            .filter(item -> item.objectiveId().equals(objectiveId))
            .mapToInt(item -> item.unknown() + item.needsSupport() + item.developing()).findFirst().orElse(0);
        String id = UUID.randomUUID().toString();
        String token = UUID.randomUUID().toString();
        Instant now = Instant.now();
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement("""
            insert into objective_intervention_drafts(id,classroom_id,course_id,objective_id,reason_code,action,
                impact_count,objective_version,confirmation_token_hash,status,created_by,created_at,confirmed_at)
            values(?,?,?,?,?,?,?,?,?,'DRAFT',?,?,null)
            """)) {
            statement.setString(1, id); statement.setString(2, classroomId); statement.setString(3, courseId);
            statement.setString(4, objectiveId); statement.setString(5, reason); statement.setString(6, normalizedAction);
            statement.setInt(7, impact); statement.setLong(8, objective.version()); statement.setString(9, sha256(token));
            statement.setString(10, actor.id()); statement.setString(11, now.toString()); statement.executeUpdate();
            return new ObjectiveInterventionDraft(id, classroomId, courseId, objectiveId, reason, normalizedAction,
                impact, objective.version(), token, "DRAFT", actor.id(), now, null);
        } catch (SQLException error) {
            throw database(error);
        }
    }

    ObjectiveInterventionDraft confirmIntervention(AuthenticatedUser actor, String courseId, String draftId,
                                                    String confirmationToken) {
        requireCourseOwner(actor, courseId);
        Instant now = Instant.now();
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                DraftRow draft = draft(connection, actor.id(), courseId, draftId);
                requireClassTeacher(actor, draft.classroomId());
                if (!"DRAFT".equals(draft.status())) throw new IllegalArgumentException("Intervention draft is not pending");
                if (!sha256(confirmationToken).equals(draft.tokenHash())) throw new SecurityException("confirmation token is invalid");
                if (draft.createdAt().plusSeconds(600).isBefore(now)) throw new IllegalArgumentException("confirmation token expired");
                CourseObjective objective = findObjective(connection, draft.objectiveId());
                if (objective.version() != draft.objectiveVersion() || objective.status() != ContentStatus.ACTIVE) {
                    throw new V19VersionConflictException("Objective changed after preview; create a new draft");
                }
                try (PreparedStatement statement = connection.prepareStatement(
                    "update objective_intervention_drafts set status='CONFIRMED',confirmed_at=? where id=? and status='DRAFT'")) {
                    statement.setString(1, now.toString()); statement.setString(2, draftId);
                    if (statement.executeUpdate() != 1) throw new V19VersionConflictException("Intervention draft changed");
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                    insert into intervention_audit_v2(id,classroom_id,student_user_id,objective_id,reason_code,
                        action,actor_user_id,created_at) values(?,?,'AGGREGATE',?,?,?,?,?)
                    """)) {
                    statement.setString(1, UUID.randomUUID().toString()); statement.setString(2, draft.classroomId());
                    statement.setString(3, draft.objectiveId()); statement.setString(4, draft.reasonCode());
                    statement.setString(5, draft.action()); statement.setString(6, actor.id());
                    statement.setString(7, now.toString()); statement.executeUpdate();
                }
                connection.commit();
                return new ObjectiveInterventionDraft(draftId, draft.classroomId(), courseId, draft.objectiveId(),
                    draft.reasonCode(), draft.action(), draft.impactCount(), draft.objectiveVersion(), "",
                    "CONFIRMED", actor.id(), draft.createdAt(), now);
            } catch (SQLException | RuntimeException error) {
                connection.rollback(); throw error;
            }
        } catch (SQLException error) {
            throw database(error);
        }
    }

    PlanningHealthSummary health(AuthenticatedUser actor) {
        if (!actor.hasRole(UserRole.ADMIN)) throw new SecurityException("administrator role required");
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            return new PlanningHealthSummary(count(statement,
                "select count(*) from course_objectives where status='ACTIVE'"), count(statement,
                "select count(*) from study_plan_sync_operations where status<>'DELIVERED'"), count(statement,
                "select count(*) from objective_intervention_drafts where status='CONFIRMED'"), count(statement,
                "select coalesce(sum(feedback_count),0) from tutor_feedback_summary"), Instant.now());
        } catch (SQLException error) {
            throw database(error);
        }
    }

    private void initialize() throws SQLException {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            try (ResultSet row = statement.executeQuery("select coalesce(max(version),0) from cloud_schema_version")) {
                if (row.next() && row.getInt(1) > 5) {
                    throw new SQLException("Cloud database schema is newer than this SQLTeacher version");
                }
            }
            statement.executeUpdate("""
                create table if not exists course_objectives(
                    id text primary key,course_id text not null references courses(id),title text not null,
                    description text not null,completion_criteria text not null,sort_order integer not null,
                    status text not null check(status in ('ACTIVE','INACTIVE')),version integer not null,
                    created_by text not null references users(id),created_at text not null,updated_at text not null)
                """);
            statement.executeUpdate("create index if not exists idx_course_objectives_order on course_objectives(course_id,status,sort_order,id)");
            statement.executeUpdate("""
                create table if not exists objective_prerequisites(
                    objective_id text not null references course_objectives(id) on delete cascade,
                    prerequisite_objective_id text not null references course_objectives(id) on delete cascade,
                    created_at text not null,primary key(objective_id,prerequisite_objective_id),
                    check(objective_id<>prerequisite_objective_id))
                """);
            statement.executeUpdate("""
                create table if not exists objective_resource_links(
                    objective_id text not null references course_objectives(id) on delete cascade,
                    resource_type text not null check(resource_type in ('KNOWLEDGE_POINT','KNOWLEDGE_ARTICLE','EXERCISE_VERSION')),
                    resource_id text not null,created_at text not null,
                    primary key(objective_id,resource_type,resource_id))
                """);
            statement.executeUpdate("""
                create table if not exists teaching_cycles(
                    id text primary key,course_id text not null references courses(id),classroom_id text,
                    phase text not null check(phase in ('BEFORE_CLASS','IN_CLASS','AFTER_CLASS','REVIEW')),
                    status text not null check(status in ('DRAFT','PUBLISHED','CLOSED')),version integer not null,
                    created_by text not null references users(id),created_at text not null,updated_at text not null)
                """);
            statement.executeUpdate("""
                create table if not exists study_plan_action_state(
                    owner_id text not null references users(id),action_id text not null,course_id text not null,
                    requested_state text not null check(requested_state in ('STARTED','COMPLETED','DISMISSED')),
                    version integer not null,updated_at text not null,primary key(owner_id,action_id))
                """);
            statement.executeUpdate("""
                create table if not exists study_plan_sync_operations(
                    owner_id text not null references users(id),operation_id text not null,status text not null,
                    created_at text not null,primary key(owner_id,operation_id))
                """);
            statement.executeUpdate("""
                create table if not exists intervention_audit_v2(
                    id text primary key,classroom_id text not null,student_user_id text not null,objective_id text not null,
                    reason_code text not null,action text not null,actor_user_id text not null,created_at text not null)
                """);
            statement.executeUpdate("""
                create table if not exists tutor_feedback_summary(
                    owner_id text not null references users(id),objective_id text not null,feedback_type text not null,
                    feedback_count integer not null,updated_at text not null,primary key(owner_id,objective_id,feedback_type))
                """);
            statement.executeUpdate("""
                create table if not exists objective_intervention_drafts(
                    id text primary key,classroom_id text not null references classrooms(id),
                    course_id text not null references courses(id),objective_id text not null references course_objectives(id),
                    reason_code text not null,action text not null,impact_count integer not null,
                    objective_version integer not null,confirmation_token_hash text not null,
                    status text not null check(status in ('DRAFT','CONFIRMED','EXPIRED')),
                    created_by text not null references users(id),created_at text not null,confirmed_at text)
                """);
            statement.executeUpdate("insert or ignore into cloud_schema_version(version,description,applied_at) values"
                + "(5,'v1.9 course objectives and deterministic study planning',current_timestamp)");
        }
    }

    private List<ObjectivePrerequisite> prerequisites(Connection connection, Set<String> objectiveIds)
        throws SQLException {
        List<ObjectivePrerequisite> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
            select p.* from objective_prerequisites p join course_objectives o on o.id=p.objective_id
            where o.course_id=? and o.status='ACTIVE'
            """)) {
            String courseId = courseId(connection, objectiveIds.iterator().next());
            statement.setString(1, courseId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (objectiveIds.contains(rows.getString("prerequisite_objective_id"))) {
                        result.add(new ObjectivePrerequisite(rows.getString("objective_id"),
                            rows.getString("prerequisite_objective_id"), Instant.parse(rows.getString("created_at"))));
                    }
                }
            }
        }
        return List.copyOf(result);
    }

    private List<ObjectiveResourceLink> resources(Connection connection, Set<String> objectiveIds, boolean owner)
        throws SQLException {
        List<ObjectiveResourceLink> result = new ArrayList<>();
        String placeholders = String.join(",", java.util.Collections.nCopies(objectiveIds.size(), "?"));
        try (PreparedStatement statement = connection.prepareStatement(
            "select * from objective_resource_links where objective_id in (" + placeholders + ") order by objective_id,resource_type,resource_id")) {
            int parameter = 1;
            for (String id : objectiveIds.stream().sorted().toList()) statement.setString(parameter++, id);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    ObjectiveResourceType type = ObjectiveResourceType.valueOf(rows.getString("resource_type"));
                    String resourceId = rows.getString("resource_id");
                    if (resourceVisible(connection, type, resourceId, owner)) {
                        result.add(new ObjectiveResourceLink(rows.getString("objective_id"), type, resourceId,
                            Instant.parse(rows.getString("created_at"))));
                    }
                }
            }
        }
        return List.copyOf(result);
    }

    private boolean resourceVisible(Connection connection, ObjectiveResourceType type, String id, boolean owner)
        throws SQLException {
        String sql = switch (type) {
            case KNOWLEDGE_POINT -> "select 1 from knowledge_points where id=? and status='ACTIVE'";
            case KNOWLEDGE_ARTICLE -> "select 1 from cloud_knowledge_articles where id=?"
                + (owner ? "" : " and visibility='PUBLISHED'");
            case EXERCISE_VERSION -> "select 1 from shared_exercise_versions v join shared_exercises e on e.id=v.exercise_id"
                + " where v.id=? and e.status='ACTIVE'";
        };
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, id);
            try (ResultSet row = statement.executeQuery()) { return row.next(); }
        }
    }

    private void requireResource(Connection connection, String courseId, ObjectiveResourceType type, String id)
        throws SQLException {
        String sql = switch (type) {
            case KNOWLEDGE_POINT -> "select 1 from knowledge_points where id=? and course_id=? and status='ACTIVE'";
            case KNOWLEDGE_ARTICLE -> "select 1 from cloud_knowledge_articles where id=? and course_id=?";
            case EXERCISE_VERSION -> "select 1 from shared_exercise_versions v join shared_exercises e on e.id=v.exercise_id"
                + " where v.id=? and e.course_id=? and e.status='ACTIVE'";
        };
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, id);
            statement.setString(2, courseId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new IllegalArgumentException("Objective resource is not active in this course");
            }
        }
    }

    private boolean createsCycle(Connection connection, String objectiveId, String prerequisiteId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            with recursive dependencies(id) as (
                select prerequisite_objective_id from objective_prerequisites where objective_id=?
                union
                select p.prerequisite_objective_id from objective_prerequisites p
                    join dependencies d on p.objective_id=d.id
            ) select 1 from dependencies where id=? limit 1
            """)) {
            statement.setString(1, prerequisiteId);
            statement.setString(2, objectiveId);
            try (ResultSet row = statement.executeQuery()) { return row.next(); }
        }
    }

    private void requireObjective(Connection connection, String courseId, String objectiveId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "select 1 from course_objectives where id=? and course_id=? and status='ACTIVE'")) {
            statement.setString(1, objectiveId);
            statement.setString(2, courseId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new IllegalArgumentException("Objective is not active in this course");
            }
        }
    }

    private boolean objectiveExists(Connection connection, String courseId, String objectiveId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "select 1 from course_objectives where id=? and course_id=?")) {
            statement.setString(1, objectiveId); statement.setString(2, courseId);
            try (ResultSet row = statement.executeQuery()) { return row.next(); }
        }
    }

    private CourseObjective findObjective(Connection connection, String objectiveId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("select * from course_objectives where id=?")) {
            statement.setString(1, objectiveId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new IllegalArgumentException("Course objective not found");
                return objective(row);
            }
        }
    }

    private StudyPlanSnapshot applyStates(Connection connection, StudyPlanSnapshot plan) throws SQLException {
        List<StudyPlanAction> actions = new ArrayList<>();
        for (StudyPlanAction action : plan.actions()) {
            StudyPlanActionStateRecord state = actionState(connection, plan.ownerId(), plan.courseId(), action.id());
            StudyPlanActionState value = state == null ? StudyPlanActionState.OPEN : state.state();
            if (value == StudyPlanActionState.COMPLETED || value == StudyPlanActionState.DISMISSED) continue;
            actions.add(new StudyPlanAction(action.id(), action.objectiveId(), action.type(), action.title(),
                action.description(), action.resourceType(), action.resourceId(), action.reasonCode(),
                action.priority(), value, action.resolutionCondition(), action.evidence(),
                state == null ? 0 : state.version()));
        }
        return new StudyPlanSnapshot(plan.ownerId(), plan.courseId(), plan.policyVersion(), plan.factWatermark(),
            plan.generatedAt(), plan.expiresAt(), actions);
    }

    private StudyPlanActionStateRecord actionState(Connection connection, String ownerId, String courseId,
                                                   String actionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            select * from study_plan_action_state where owner_id=? and course_id=? and action_id=?
            """)) {
            statement.setString(1, ownerId); statement.setString(2, courseId); statement.setString(3, actionId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return null;
                return new StudyPlanActionStateRecord(ownerId, courseId, actionId,
                    StudyPlanActionState.valueOf(row.getString("requested_state")), row.getLong("version"),
                    Instant.parse(row.getString("updated_at")));
            }
        }
    }

    private boolean operationExists(Connection connection, String ownerId, String operationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "select 1 from study_plan_sync_operations where owner_id=? and operation_id=?")) {
            statement.setString(1, ownerId); statement.setString(2, operationId);
            try (ResultSet row = statement.executeQuery()) { return row.next(); }
        }
    }

    private void requireClassTeacher(AuthenticatedUser actor, String classroomId) {
        if (actor.hasRole(UserRole.ADMIN)) return;
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
            "select 1 from classroom_members where classroom_id=? and user_id=? and role='TEACHER'")) {
            statement.setString(1, classroomId); statement.setString(2, actor.id());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new SecurityException("classroom teacher role required");
            }
        } catch (SQLException error) {
            throw database(error);
        }
    }

    private List<String> classroomStudents(Connection connection, String classroomId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "select user_id from classroom_members where classroom_id=? and role='STUDENT' order by user_id")) {
            statement.setString(1, classroomId); List<String> result = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) { while (rows.next()) result.add(rows.getString(1)); }
            return List.copyOf(result);
        }
    }

    private Set<String> objectiveKnowledgePoints(Connection connection, String objectiveId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            select resource_id from objective_resource_links where objective_id=? and resource_type='KNOWLEDGE_POINT'
            """)) {
            statement.setString(1, objectiveId); Set<String> result = new java.util.LinkedHashSet<>();
            try (ResultSet rows = statement.executeQuery()) { while (rows.next()) result.add(rows.getString(1)); }
            return Set.copyOf(result);
        }
    }

    private Map<String, Map<String, EvidenceTotals>> objectiveEvidence(Connection connection, String classroomId,
                                                                       Map<String, Set<String>> pointsByObjective)
        throws SQLException {
        Map<String, Map<String, EvidenceTotals>> totals = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
            select s.user_id,s.status,snap.knowledge_point_ids_json from assignment_submissions s
            join assignment_content_snapshots snap on snap.assignment_id=s.assignment_id
            where s.classroom_id=? order by s.submitted_at,s.id
            """)) {
            statement.setString(1, classroomId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    Set<String> submittedPoints = readIds(rows.getString(3));
                    for (Map.Entry<String, Set<String>> entry : pointsByObjective.entrySet()) {
                        if (java.util.Collections.disjoint(entry.getValue(), submittedPoints)) continue;
                        EvidenceTotals value = totals.computeIfAbsent(entry.getKey(), ignored -> new HashMap<>())
                            .computeIfAbsent(rows.getString(1), ignored -> new EvidenceTotals());
                        value.attempts++;
                        if ("PASSED".equals(rows.getString(2))) value.passes++;
                    }
                }
            }
        }
        return totals;
    }

    private static Set<String> readIds(String json) {
        if (json == null || json.isBlank()) return Set.of();
        try { return Set.copyOf(JSON.readValue(json, new TypeReference<List<String>>() { })); }
        catch (java.io.IOException error) { throw new IllegalArgumentException("Stored knowledge-point IDs are invalid", error); }
    }

    private DraftRow draft(Connection connection, String actorId, String courseId, String draftId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "select * from objective_intervention_drafts where id=? and course_id=? and created_by=?")) {
            statement.setString(1, draftId); statement.setString(2, courseId); statement.setString(3, actorId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new SecurityException("intervention draft is not visible");
                return new DraftRow(row.getString("classroom_id"), row.getString("objective_id"),
                    row.getString("reason_code"), row.getString("action"), row.getInt("impact_count"),
                    row.getLong("objective_version"), row.getString("confirmation_token_hash"),
                    row.getString("status"), Instant.parse(row.getString("created_at")));
            }
        }
    }

    private static String required(String value, String name, int max) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        String normalized = value.trim();
        if (normalized.length() > max) throw new IllegalArgumentException(name + " is too long");
        return normalized;
    }

    private static String sha256(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("confirmationToken must not be blank");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static int count(Statement statement, String sql) throws SQLException {
        try (ResultSet row = statement.executeQuery(sql)) { return row.next() ? row.getInt(1) : 0; }
    }

    private record DraftRow(String classroomId, String objectiveId, String reasonCode, String action,
                            int impactCount, long objectiveVersion, String tokenHash, String status,
                            Instant createdAt) { }

    private static final class EvidenceTotals {
        int attempts;
        int passes;
    }

    private String courseId(Connection connection, String objectiveId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("select course_id from course_objectives where id=?")) {
            statement.setString(1, objectiveId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new IllegalArgumentException("Objective not found");
                return row.getString(1);
            }
        }
    }

    private boolean requireCourseReadable(AuthenticatedUser actor, String courseId) {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
            "select created_by,status from courses where id=?")) {
            statement.setString(1, courseId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new IllegalArgumentException("Course not found");
                boolean owner = actor.hasRole(UserRole.ADMIN) || actor.id().equals(row.getString("created_by"));
                if (!owner && !ContentStatus.ACTIVE.name().equals(row.getString("status"))) {
                    throw new SecurityException("course is not visible");
                }
                return owner;
            }
        } catch (SQLException error) {
            throw database(error);
        }
    }

    private void requireCourseOwner(AuthenticatedUser actor, String courseId) {
        if (!actor.hasRole(UserRole.TEACHER) && !actor.hasRole(UserRole.ADMIN)) {
            throw new SecurityException("teacher role required");
        }
        if (actor.hasRole(UserRole.ADMIN)) return;
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
            "select 1 from courses where id=? and created_by=?")) {
            statement.setString(1, courseId);
            statement.setString(2, actor.id());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new SecurityException("course owner required");
            }
        } catch (SQLException error) {
            throw database(error);
        }
    }

    private CourseObjective objective(ResultSet row) throws SQLException {
        return new CourseObjective(row.getString("id"), row.getString("course_id"), row.getString("title"),
            row.getString("description"), row.getString("completion_criteria"), row.getInt("sort_order"),
            ContentStatus.valueOf(row.getString("status")), row.getLong("version"), row.getString("created_by"),
            Instant.parse(row.getString("created_at")), Instant.parse(row.getString("updated_at")));
    }

    private Connection open() throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("pragma foreign_keys=on");
            statement.executeUpdate("pragma busy_timeout=5000");
        }
        return connection;
    }

    private SqlTeacherException database(SQLException error) {
        return new SqlTeacherException("CLOUD_DATABASE_FAILED", "Cloud planning database operation failed", error);
    }
}

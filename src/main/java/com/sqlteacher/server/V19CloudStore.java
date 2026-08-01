package com.sqlteacher.server;

import com.sqlteacher.application.collaboration.AuthenticatedUser;
import com.sqlteacher.application.collaboration.ContentStatus;
import com.sqlteacher.application.collaboration.UserRole;
import com.sqlteacher.application.planning.CourseObjective;
import com.sqlteacher.application.planning.DeterministicStudyPlanService;
import com.sqlteacher.application.planning.ObjectivePrerequisite;
import com.sqlteacher.application.planning.ObjectiveResourceLink;
import com.sqlteacher.application.planning.ObjectiveResourceType;
import com.sqlteacher.application.planning.StudyPlanSnapshot;
import com.sqlteacher.domain.SqlTeacherException;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** v1.9 course objective graph and deterministic study-plan persistence. */
final class V19CloudStore {
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
            return plans.generate(actor.id(), courseId, objectives, prerequisites, resources, Set.of(), Instant.now());
        } catch (SQLException error) {
            throw database(error);
        }
    }

    private void initialize() throws SQLException {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
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

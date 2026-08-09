package com.sqlteacher.infrastructure.database;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlteacher.application.activity.ActivityEvaluationDispatcher;
import com.sqlteacher.application.activity.ActivityEvaluationResult;
import com.sqlteacher.application.activity.ActivityFeedback;
import com.sqlteacher.application.activity.ActivityLearningService;
import com.sqlteacher.application.activity.ActivitySubmission;
import com.sqlteacher.application.event.LearningEventOwnerProvider;
import com.sqlteacher.application.event.LearningEventService;
import com.sqlteacher.application.runner.RunnerCancellation;
import com.sqlteacher.domain.SqlTeacherException;
import com.sqlteacher.domain.activity.ActivityArtifact;
import com.sqlteacher.domain.activity.ActivityDifficulty;
import com.sqlteacher.domain.activity.ActivitySpecification;
import com.sqlteacher.domain.activity.ActivityType;
import com.sqlteacher.domain.activity.CodeActivitySpecification;
import com.sqlteacher.domain.activity.LearningActivityDefinition;
import com.sqlteacher.domain.activity.QuizActivitySpecification;
import com.sqlteacher.domain.activity.ProjectActivitySpecification;
import com.sqlteacher.domain.activity.ProjectActivityArtifact;
import com.sqlteacher.domain.activity.SimulationActivitySpecification;
import com.sqlteacher.domain.activity.TraceActivitySpecification;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Persists non-SQL activity sessions and deterministic evaluation evidence. */
public final class JdbcActivityLearningService implements ActivityLearningService {
    private final JdbcConnectionFactory connectionFactory;
    private final LearningEventOwnerProvider ownerProvider;
    private final LearningEventService eventService;
    private final ActivityEvaluationDispatcher dispatcher;
    private final ObjectMapper json;
    private final Clock clock;

    public JdbcActivityLearningService(JdbcConnectionFactory connectionFactory,
                                       LearningEventOwnerProvider ownerProvider,
                                       LearningEventService eventService,
                                       ActivityEvaluationDispatcher dispatcher) {
        this(connectionFactory, ownerProvider, eventService, dispatcher, Clock.systemUTC());
    }

    JdbcActivityLearningService(JdbcConnectionFactory connectionFactory,
                                LearningEventOwnerProvider ownerProvider,
                                LearningEventService eventService,
                                ActivityEvaluationDispatcher dispatcher,
                                Clock clock) {
        this.connectionFactory = Objects.requireNonNull(connectionFactory);
        this.ownerProvider = Objects.requireNonNull(ownerProvider);
        this.eventService = Objects.requireNonNull(eventService);
        this.dispatcher = Objects.requireNonNull(dispatcher);
        this.clock = Objects.requireNonNull(clock);
        this.json = new ObjectMapper().findAndRegisterModules();
    }

    @Override
    public LearningActivityDefinition loadDefinition(String activityId) {
        if (activityId == null || activityId.isBlank()) throw new IllegalArgumentException("activityId must not be blank");
        try (Connection connection = connectionFactory.open("app")) {
            return loadDefinition(connection, activityId.trim());
        } catch (SQLException | JsonProcessingException error) {
            throw new SqlTeacherException("ACTIVITY_DEFINITION_LOAD_FAILED", "Failed to load activity definition", error);
        }
    }

    @Override
    public ActivitySubmission submit(String activityId, ActivityArtifact artifact, RunnerCancellation cancellation) {
        Objects.requireNonNull(artifact, "artifact must not be null");
        Objects.requireNonNull(cancellation, "cancellation must not be null");
        LearningActivityDefinition definition = loadDefinition(activityId);
        if (artifact instanceof ProjectActivityArtifact project
                && project.submissionVersion() != nextSubmissionVersion(activityId)) {
            throw new SqlTeacherException("PROJECT_VERSION_CONFLICT",
                "Project submission version is stale; refresh before submitting");
        }
        ActivityEvaluationResult evaluation = dispatcher.evaluate(definition, artifact, cancellation);
        if (cancellation.isCancelled()) {
            throw new SqlTeacherException("ACTIVITY_CANCELLED", "Activity evaluation was cancelled");
        }
        Instant occurredAt = clock.instant();
        String sessionId = UUID.randomUUID().toString();
        String evaluationId = UUID.randomUUID().toString();
        String ownerId = normalizedOwner();
        try (Connection connection = connectionFactory.open("app")) {
            connection.setAutoCommit(false);
            try {
                insertSession(connection, sessionId, ownerId, definition, occurredAt);
                insertEvaluation(connection, evaluationId, ownerId, definition, artifact, evaluation, occurredAt);
                connection.commit();
            } catch (SQLException | JsonProcessingException error) {
                connection.rollback();
                throw error;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException | JsonProcessingException error) {
            throw new SqlTeacherException("ACTIVITY_SUBMISSION_SAVE_FAILED", "Failed to save activity submission", error);
        }
        eventService.recordActivityEvaluation(definition.id(), definition.type(), evaluation.status().name(),
            evaluation.passed(), evaluation.resourceUsage().wallTime(), evaluation.evaluatorVersion(),
            evaluation.evidenceVersion(), evaluation.reasonCode());
        return new ActivitySubmission(sessionId, evaluationId, evaluation, occurredAt);
    }

    @Override
    public java.util.Optional<ActivityFeedback> latestFeedback(String activityId) {
        if (activityId == null || activityId.isBlank()) throw new IllegalArgumentException("activityId must not be blank");
        try (Connection connection = connectionFactory.open("app");
             PreparedStatement statement = connection.prepareStatement("""
                 select id,activity_id,author_id,comment,created_at from activity_feedback
                 where owner_id=? and activity_id=? and status='PUBLISHED'
                 order by created_at desc,id desc limit 1
                 """)) {
            statement.setString(1, normalizedOwner());
            statement.setString(2, activityId.trim());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return java.util.Optional.empty();
                return java.util.Optional.of(new ActivityFeedback(
                    row.getString(1), row.getString(2), row.getString(3), row.getString(4),
                    Instant.parse(row.getString(5))
                ));
            }
        } catch (SQLException error) {
            throw new SqlTeacherException("ACTIVITY_FEEDBACK_LOAD_FAILED", "Failed to load activity feedback", error);
        }
    }

    @Override
    public int nextSubmissionVersion(String activityId) {
        if (activityId == null || activityId.isBlank()) throw new IllegalArgumentException("activityId must not be blank");
        try (Connection connection = connectionFactory.open("app");
             PreparedStatement statement = connection.prepareStatement("""
                 select count(*) from activity_evaluation_result
                 where owner_id=? and activity_id=? and activity_type='PROJECT'
                 """)) {
            statement.setString(1, normalizedOwner());
            statement.setString(2, activityId.trim());
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? row.getInt(1) + 1 : 1;
            }
        } catch (SQLException error) {
            throw new SqlTeacherException("PROJECT_VERSION_LOAD_FAILED", "Failed to load project version", error);
        }
    }

    private LearningActivityDefinition loadDefinition(Connection connection, String activityId)
            throws SQLException, JsonProcessingException {
        try (PreparedStatement statement = connection.prepareStatement("""
            select id,course_id,section_id,activity_type,title,description,difficulty,estimated_minutes,
                definition_version,specification_json,enabled,created_at,updated_at
            from learning_activity_definition where id=? and enabled=1
            """)) {
            statement.setString(1, activityId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new SqlTeacherException("ACTIVITY_NOT_FOUND", "Activity is not available: " + activityId);
                ActivityType type = ActivityType.valueOf(row.getString("activity_type"));
                ActivitySpecification specification = decode(type, row.getString("specification_json"));
                return new LearningActivityDefinition(
                    row.getString("id"), row.getString("course_id"), row.getString("section_id"),
                    row.getString("title"), row.getString("description"), knowledgePoints(connection, activityId),
                    ActivityDifficulty.valueOf(row.getString("difficulty")), row.getInt("estimated_minutes"),
                    row.getInt("definition_version"), row.getBoolean("enabled"), specification,
                    Instant.parse(row.getString("created_at")), Instant.parse(row.getString("updated_at"))
                );
            }
        }
    }

    private ActivitySpecification decode(ActivityType type, String specificationJson) throws JsonProcessingException {
        return switch (type) {
            case CODE -> json.readValue(specificationJson, CodeActivitySpecification.class);
            case PROJECT -> json.readValue(specificationJson, ProjectActivitySpecification.class);
            case QUIZ -> json.readValue(specificationJson, QuizActivitySpecification.class);
            case SIMULATION -> json.readValue(specificationJson, SimulationActivitySpecification.class);
            case TRACE -> json.readValue(specificationJson, TraceActivitySpecification.class);
            default -> throw new SqlTeacherException("ACTIVITY_TYPE_UNSUPPORTED",
                "The generic learning service does not load " + type + " activities");
        };
    }

    private static List<String> knowledgePoints(Connection connection, String activityId) throws SQLException {
        List<String> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
            select k.id from activity_knowledge_point a
            join knowledge_point_definition k on k.id=a.knowledge_point_id
            where a.activity_id=? order by k.id
            """)) {
            statement.setString(1, activityId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(rows.getString(1));
            }
        }
        return List.copyOf(result);
    }

    private static void insertSession(Connection connection, String sessionId, String ownerId,
                                      LearningActivityDefinition definition, Instant occurredAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            insert into activity_session(id,owner_id,activity_id,activity_version,status,source_kind,source_id,
                started_at,updated_at) values (?,?,?,?,?,?,?,?,?)
            """)) {
            statement.setString(1, sessionId); statement.setString(2, ownerId);
            statement.setString(3, definition.id()); statement.setInt(4, definition.version());
            statement.setString(5, "COMPLETED"); statement.setString(6, "V2_ACTIVITY_SUBMISSION");
            statement.setString(7, sessionId); statement.setString(8, occurredAt.toString());
            statement.setString(9, occurredAt.toString()); statement.executeUpdate();
        }
    }

    private void insertEvaluation(Connection connection, String evaluationId, String ownerId,
                                  LearningActivityDefinition definition, ActivityArtifact artifact,
                                  ActivityEvaluationResult evaluation, Instant occurredAt)
            throws SQLException, JsonProcessingException {
        try (PreparedStatement statement = connection.prepareStatement("""
            insert into activity_evaluation_result(id,owner_id,activity_id,activity_version,activity_type,status,
                reason_code,criteria_json,evidence_summary_json,evaluator_version,evidence_version,duration_ms,
                artifact_hash,occurred_at) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """)) {
            statement.setString(1, evaluationId); statement.setString(2, ownerId);
            statement.setString(3, definition.id()); statement.setInt(4, definition.version());
            statement.setString(5, definition.type().name()); statement.setString(6, evaluation.status().name());
            statement.setString(7, evaluation.reasonCode()); statement.setString(8, json.writeValueAsString(evaluation.criteria()));
            statement.setString(9, json.writeValueAsString(java.util.Map.of("summary", evaluation.summary())));
            statement.setString(10, evaluation.evaluatorVersion()); statement.setString(11, evaluation.evidenceVersion());
            statement.setLong(12, evaluation.resourceUsage().wallTime().toMillis());
            statement.setString(13, sha256(json.writeValueAsBytes(artifact)));
            statement.setString(14, occurredAt.toString()); statement.executeUpdate();
        }
    }

    private String normalizedOwner() {
        String owner = ownerProvider.currentOwnerId();
        return owner == null || owner.isBlank() ? LearningEventOwnerProvider.GUEST_OWNER : owner.trim();
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}

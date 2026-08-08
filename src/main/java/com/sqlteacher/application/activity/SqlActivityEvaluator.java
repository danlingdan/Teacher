package com.sqlteacher.application.activity;

import com.sqlteacher.application.exercise.ExerciseEvaluationResult;
import com.sqlteacher.application.exercise.SqlExerciseEvaluationService;
import com.sqlteacher.domain.activity.ActivityType;
import com.sqlteacher.domain.activity.LearningActivityDefinition;
import com.sqlteacher.domain.activity.SqlActivityArtifact;
import com.sqlteacher.domain.activity.SqlActivitySpecification;
import com.sqlteacher.domain.exercise.ExerciseDefinition;
import com.sqlteacher.domain.exercise.ExerciseDifficulty;

import java.util.Objects;

public final class SqlActivityEvaluator implements ActivityEvaluator<SqlActivitySpecification, SqlActivityArtifact> {
    public static final String VERSION = "sql-deterministic-v1";
    public static final String EVIDENCE_VERSION = "activity-evidence-v1";

    private final SqlExerciseEvaluationService delegate;

    public SqlActivityEvaluator(SqlExerciseEvaluationService delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
    }

    @Override public ActivityType activityType() { return ActivityType.SQL; }
    @Override public Class<SqlActivitySpecification> specificationType() { return SqlActivitySpecification.class; }
    @Override public Class<SqlActivityArtifact> artifactType() { return SqlActivityArtifact.class; }
    @Override public String evaluatorVersion() { return VERSION; }

    @Override
    public ActivityEvaluationResult evaluate(
        LearningActivityDefinition definition,
        SqlActivitySpecification specification,
        SqlActivityArtifact artifact
    ) {
        ExerciseDefinition exercise = new ExerciseDefinition(
            definition.id(), definition.title(), definition.description(),
            specification.knowledgePoint(), ExerciseDifficulty.valueOf(definition.difficulty().name()),
            specification.dataset().id(), specification.referenceSql(), specification.evaluationRule(),
            specification.hints(), definition.version(), definition.enabled(), definition.createdAt(), definition.updatedAt()
        );
        ExerciseEvaluationResult result = delegate.evaluate(exercise, specification.dataset(), artifact.submittedSql());
        return new ActivityEvaluationResult(
            result.passed() ? ActivityEvaluationStatus.PASSED : ActivityEvaluationStatus.FAILED,
            result.criteria().stream().map(item -> new ActivityCriterionResult(
                item.criterion(), item.passed(), item.passed() ? "" : result.errorCode(), item.feedback()
            )).toList(),
            result.feedback(), result.errorCode(), VERSION, EVIDENCE_VERSION,
            ActivityResourceUsage.evaluationOnly(result.duration())
        );
    }
}

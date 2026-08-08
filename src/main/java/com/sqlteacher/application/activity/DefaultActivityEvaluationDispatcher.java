package com.sqlteacher.application.activity;

import com.sqlteacher.domain.activity.ActivityArtifact;
import com.sqlteacher.domain.activity.ActivitySpecification;
import com.sqlteacher.domain.activity.ActivityType;
import com.sqlteacher.domain.activity.LearningActivityDefinition;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class DefaultActivityEvaluationDispatcher implements ActivityEvaluationDispatcher {
    private final Map<ActivityType, ActivityEvaluator<?, ?>> evaluators;

    public DefaultActivityEvaluationDispatcher(List<ActivityEvaluator<?, ?>> evaluators) {
        Objects.requireNonNull(evaluators, "evaluators must not be null");
        EnumMap<ActivityType, ActivityEvaluator<?, ?>> registered = new EnumMap<>(ActivityType.class);
        for (ActivityEvaluator<?, ?> evaluator : evaluators) {
            Objects.requireNonNull(evaluator, "evaluator must not be null");
            if (registered.putIfAbsent(evaluator.activityType(), evaluator) != null) {
                throw new IllegalArgumentException("Duplicate evaluator for " + evaluator.activityType());
            }
        }
        this.evaluators = Map.copyOf(registered);
    }

    @Override
    public ActivityEvaluationResult evaluate(LearningActivityDefinition definition, ActivityArtifact artifact) {
        Objects.requireNonNull(definition, "definition must not be null");
        Objects.requireNonNull(artifact, "artifact must not be null");
        ActivityEvaluator<?, ?> evaluator = evaluators.get(definition.type());
        if (evaluator == null) {
            throw new UnsupportedActivityException(
                "ACTIVITY_TYPE_UNSUPPORTED", "No evaluator is registered for " + definition.type()
            );
        }
        if (artifact.type() != definition.type()
                || !evaluator.specificationType().isInstance(definition.specification())
                || !evaluator.artifactType().isInstance(artifact)) {
            throw new UnsupportedActivityException(
                "ACTIVITY_ARTIFACT_UNSUPPORTED", "The artifact does not match the activity definition"
            );
        }
        return evaluateTyped(evaluator, definition, definition.specification(), artifact);
    }

    private static <S extends ActivitySpecification, A extends ActivityArtifact> ActivityEvaluationResult evaluateTyped(
        ActivityEvaluator<S, A> evaluator,
        LearningActivityDefinition definition,
        ActivitySpecification specification,
        ActivityArtifact artifact
    ) {
        return evaluator.evaluate(
            definition,
            evaluator.specificationType().cast(specification),
            evaluator.artifactType().cast(artifact)
        );
    }
}

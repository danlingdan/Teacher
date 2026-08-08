package com.sqlteacher.application.activity;

import com.sqlteacher.application.runner.RunnerCancellation;
import com.sqlteacher.domain.activity.ActivityArtifact;
import com.sqlteacher.domain.activity.ActivitySpecification;
import com.sqlteacher.domain.activity.LearningActivityDefinition;

public interface CancellableActivityEvaluator<S extends ActivitySpecification, A extends ActivityArtifact>
        extends ActivityEvaluator<S, A> {
    ActivityEvaluationResult evaluate(LearningActivityDefinition definition, S specification, A artifact,
                                      RunnerCancellation cancellation);

    @Override
    default ActivityEvaluationResult evaluate(LearningActivityDefinition definition, S specification, A artifact) {
        return evaluate(definition, specification, artifact, RunnerCancellation.NONE);
    }
}

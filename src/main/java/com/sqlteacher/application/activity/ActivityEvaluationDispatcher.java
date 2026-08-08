package com.sqlteacher.application.activity;

import com.sqlteacher.domain.activity.ActivityArtifact;
import com.sqlteacher.domain.activity.LearningActivityDefinition;
import com.sqlteacher.application.runner.RunnerCancellation;

public interface ActivityEvaluationDispatcher {
    ActivityEvaluationResult evaluate(LearningActivityDefinition definition, ActivityArtifact artifact);

    default ActivityEvaluationResult evaluate(LearningActivityDefinition definition, ActivityArtifact artifact,
                                              RunnerCancellation cancellation) {
        return evaluate(definition, artifact);
    }
}

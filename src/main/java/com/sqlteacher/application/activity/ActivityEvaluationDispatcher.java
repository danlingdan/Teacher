package com.sqlteacher.application.activity;

import com.sqlteacher.domain.activity.ActivityArtifact;
import com.sqlteacher.domain.activity.LearningActivityDefinition;

public interface ActivityEvaluationDispatcher {
    ActivityEvaluationResult evaluate(LearningActivityDefinition definition, ActivityArtifact artifact);
}

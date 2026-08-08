package com.sqlteacher.application.activity;

import com.sqlteacher.domain.activity.ActivityArtifact;
import com.sqlteacher.domain.activity.ActivitySpecification;
import com.sqlteacher.domain.activity.ActivityType;
import com.sqlteacher.domain.activity.LearningActivityDefinition;

public interface ActivityEvaluator<S extends ActivitySpecification, A extends ActivityArtifact> {
    ActivityType activityType();

    Class<S> specificationType();

    Class<A> artifactType();

    String evaluatorVersion();

    ActivityEvaluationResult evaluate(LearningActivityDefinition definition, S specification, A artifact);
}

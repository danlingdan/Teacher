package com.sqlteacher.application.activity;

import com.sqlteacher.domain.activity.ActivityArtifact;
import com.sqlteacher.domain.activity.LearningActivityDefinition;
import com.sqlteacher.application.runner.RunnerCancellation;

public interface ActivityLearningService {
    LearningActivityDefinition loadDefinition(String activityId);

    default ActivitySubmission submit(String activityId, ActivityArtifact artifact) {
        return submit(activityId, artifact, RunnerCancellation.NONE);
    }

    ActivitySubmission submit(String activityId, ActivityArtifact artifact, RunnerCancellation cancellation);

    java.util.Optional<ActivityFeedback> latestFeedback(String activityId);
}

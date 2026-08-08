package com.sqlteacher.application.activity;

import com.sqlteacher.application.collaboration.DesktopAccessProfile;

import java.util.Optional;

public interface ActivityReviewService {
    Optional<ActivityReviewItem> latest(DesktopAccessProfile reviewer, String activityId);

    ActivityFeedback publish(DesktopAccessProfile reviewer, String evaluationId, String comment);
}

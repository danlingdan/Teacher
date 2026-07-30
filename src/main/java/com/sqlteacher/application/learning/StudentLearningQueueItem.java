package com.sqlteacher.application.learning;

import com.sqlteacher.application.collaboration.AssignmentTaskContext;

import java.util.Objects;

public record StudentLearningQueueItem(
    LearningAction action,
    AssignmentTaskContext assignmentTask,
    String notificationId
) {
    public StudentLearningQueueItem {
        Objects.requireNonNull(action, "action must not be null");
        notificationId = notificationId == null ? "" : notificationId.trim();
    }
}

package com.sqlteacher.application.learning;

import java.util.List;
import java.util.Objects;

public record StudentLearningQueue(LearningDashboard dashboard, List<StudentLearningQueueItem> items,
                                   boolean cloudAvailable) {
    public StudentLearningQueue {
        Objects.requireNonNull(dashboard, "dashboard must not be null");
        items = List.copyOf(Objects.requireNonNull(items, "items must not be null"));
    }
}

package com.sqlteacher.desktop.navigation;

import com.sqlteacher.application.course.CourseMapActivity;

import java.util.List;

/** Retains a deep-linked activity while the asynchronous catalog is still loading. */
public final class PendingActivitySelection {
    private String requestedId = "";

    public void request(String activityId) {
        requestedId = activityId == null ? "" : activityId.trim();
    }

    public int resolve(List<CourseMapActivity> activities) {
        if (requestedId.isEmpty()) return -1;
        for (int index = 0; index < activities.size(); index++) {
            if (activities.get(index).id().equals(requestedId)) {
                requestedId = "";
                return index;
            }
        }
        return -1;
    }

    public void clear() {
        requestedId = "";
    }
}
